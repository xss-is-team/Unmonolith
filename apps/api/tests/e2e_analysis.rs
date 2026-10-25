//! Whole-project analysis, complexity, graph/CFG contracts and source levels against the real parser.

mod common;

use common::*;
use serde_json::{json, Value};

const MAIN: &str = "com.legacy.app.Main.main(String[])";
const ALGO: &str = "src/main/java/com/legacy/util/Algorithms.java";

fn node_by_qualified<'a>(graph: &'a Value, name: &str) -> &'a Value {
    graph["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|n| n["qualified_name"] == name)
        .unwrap_or_else(|| panic!("no type {name} in graph"))
}

fn node_by_method<'a>(graph: &'a Value, id: &str) -> &'a Value {
    graph["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|n| n["method_id"] == id)
        .unwrap_or_else(|| panic!("no callable {id} in graph"))
}

fn has_edge(graph: &Value, from: &str, to: &str, kind: &str) -> bool {
    graph["edges"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["from"] == from && e["to"] == to && e["kind"] == kind)
}

#[tokio::test]
async fn java8_multi_package_project_is_fully_analysed() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("legacy_java8").await;
    let analysis = project.analyze("8").await;

    assert_eq!(
        analysis["status"], "completed",
        "{}",
        analysis["diagnostics"]
    );
    assert_eq!(analysis["revision"], 1);
    let ids: Vec<&str> = analysis["methods"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["id"].as_str().unwrap())
        .collect();
    for expected in [
        MAIN,
        "com.legacy.service.Ledger.find(int)",
        "com.legacy.service.Ledger.contains(int)",
        "com.legacy.util.Algorithms.binarySearch(int[],int)",
        "com.legacy.util.Algorithms.fibonacci(int)",
        "com.legacy.model.Account.getBalanceCents()",
    ] {
        assert!(
            ids.contains(&expected),
            "missing method {expected}; have {ids:?}"
        );
    }
    let unique: std::collections::HashSet<_> = ids.iter().collect();
    assert_eq!(
        unique.len(),
        ids.len(),
        "method ids are unique across packages"
    );
    // every method knows the file it is declared in
    let method = analysis["methods"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == "com.legacy.util.Algorithms.fibonacci(int)")
        .unwrap();
    assert_eq!(method["source_range"]["file"], ALGO);
    assert!(method["source_range"]["line"].as_u64().unwrap() > 1);

    // dependencies across files in the project graph
    let graph = project.graph().await;
    assert_eq!(graph["scope"], "project");
    let main = node_by_method(&graph, MAIN)["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let find = node_by_method(&graph, "com.legacy.service.Ledger.find(int)")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let contains = node_by_method(&graph, "com.legacy.service.Ledger.contains(int)")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let binary = node_by_method(&graph, "com.legacy.util.Algorithms.binarySearch(int[],int)")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let fib = node_by_method(&graph, "com.legacy.util.Algorithms.fibonacci(int)")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(
        has_edge(&graph, &main, &find, "calls"),
        "Main -> Ledger.find"
    );
    assert!(
        has_edge(&graph, &main, &fib, "calls"),
        "Main -> Algorithms.fibonacci"
    );
    assert!(
        has_edge(&graph, &contains, &binary, "calls"),
        "Ledger.contains -> Algorithms.binarySearch"
    );
    assert!(has_edge(&graph, &fib, &fib, "calls"), "fibonacci recurses");
    let account = node_by_qualified(&graph, "com.legacy.model.Account");
    let identifiable = node_by_qualified(&graph, "com.legacy.model.Identifiable");
    assert!(has_edge(
        &graph,
        account["id"].as_str().unwrap(),
        identifiable["id"].as_str().unwrap(),
        "implements"
    ));
    assert_eq!(
        account["file"],
        "src/main/java/com/legacy/model/Account.java"
    );
    let ledger = node_by_qualified(&graph, "com.legacy.service.Ledger")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let algorithms = node_by_qualified(&graph, "com.legacy.util.Algorithms")["id"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(
        has_edge(&graph, &ledger, &algorithms, "depends_on"),
        "class dependency across packages"
    );
}

#[tokio::test]
async fn method_cfgs_are_navigable_and_ranges_point_at_the_source() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("legacy_java8").await;
    project.analyze("8").await;

    let id = "com.legacy.util.Algorithms.binarySearch(int[],int)";
    let methods = project.methods().await;
    let entry = methods["methods"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == id)
        .unwrap();
    assert_eq!(entry["source_range"]["file"], ALGO);
    let selector = entry["selector"].as_str().unwrap();
    assert!(
        selector.starts_with('f'),
        "project selectors carry a file index: {selector}"
    );

    let cfg = project.cfg(id).await;
    assert_eq!(cfg["method_id"], id);
    let nodes = cfg["nodes"].as_array().unwrap();
    let edges = cfg["edges"].as_array().unwrap();
    let node_ids: std::collections::HashSet<&str> =
        nodes.iter().map(|n| n["id"].as_str().unwrap()).collect();
    assert_eq!(node_ids.len(), nodes.len(), "node ids are unique");
    assert!(
        node_ids.contains(cfg["entry"].as_str().unwrap())
            && node_ids.contains(cfg["exit"].as_str().unwrap())
    );
    for edge in edges {
        assert!(
            node_ids.contains(edge["from"].as_str().unwrap())
                && node_ids.contains(edge["to"].as_str().unwrap())
        );
    }
    // loop condition has a true edge and a back edge; the early `return mid;` reaches the exit
    let loop_node = nodes
        .iter()
        .find(|n| n["text"] == "low <= high")
        .expect("while condition node");
    assert_eq!(loop_node["kind"], "branch");
    assert!(edges
        .iter()
        .any(|e| e["from"] == loop_node["id"] && e["kind"] == "true"));
    assert!(
        edges
            .iter()
            .any(|e| e["to"] == loop_node["id"] && e["back"] == true),
        "loop back edge"
    );
    let ret = nodes
        .iter()
        .find(|n| n["text"] == "return mid;")
        .expect("return node");
    assert!(edges
        .iter()
        .any(|e| e["from"] == ret["id"] && e["to"] == cfg["exit"]));

    // source ranges slice the original text (ASCII fixture: offsets are byte offsets)
    let source = project.source(ALGO, None).await;
    for node in nodes {
        if let Some(text) = node["text"].as_str() {
            let range = &node["source_range"];
            let start = range["start"].as_u64().unwrap() as usize;
            let length = range["length"].as_u64().unwrap() as usize;
            assert_eq!(
                &source[start..start + length],
                text,
                "range of node {}",
                node["id"]
            );
            assert_eq!(range["file"], ALGO);
        }
    }
    // unknown selector
    project
        .api
        .get(&project.path("/methods/f9m9/cfg"))
        .await
        .expect_error(404, "method_not_found");
}

#[tokio::test]
async fn big_o_results_for_known_algorithms_are_grounded() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("legacy_java8").await;
    project.analyze("8").await;

    let expectations = [
        ("com.legacy.util.Algorithms.identity(int)", "O(1)"),
        ("com.legacy.util.Algorithms.sum(int[])", "O(n)"),
        (
            "com.legacy.util.Algorithms.countEqualPairs(int[],int[])",
            "O(n*m)",
        ),
        (
            "com.legacy.util.Algorithms.binarySearch(int[],int)",
            "O(log n)",
        ),
        ("com.legacy.util.Algorithms.fibonacci(int)", "O(2^n)"),
        (
            "com.legacy.service.Ledger.totalBalance(List<Account>)",
            "O(n)",
        ),
    ];
    for (method, time) in expectations {
        let entry = project.complexity_of(method).await;
        assert_eq!(entry["time_complexity"], time, "{method}: {entry}");
        assert_eq!(entry["analysis_engine"], "jdt-static-cost-model/2");
        assert_eq!(
            entry["space_complexity"],
            Value::Null,
            "space is not estimated"
        );
        assert!(!entry["limitations"].as_array().unwrap().is_empty());
        assert!(entry["source_range"]["file"]
            .as_str()
            .unwrap()
            .ends_with(".java"));
        assert!(!entry["cfg_node_ids"].as_array().unwrap().is_empty());
        assert!(
            ["high", "medium"].contains(&entry["confidence"].as_str().unwrap()),
            "{entry}"
        );
    }
    // exponential bound is flagged as an upper bound; binary search names its assumption
    let fib = project
        .complexity_of("com.legacy.util.Algorithms.fibonacci(int)")
        .await;
    assert!(fib["assumptions"].to_string().contains("upper bound"));
    let binary = project
        .complexity_of("com.legacy.util.Algorithms.binarySearch(int[],int)")
        .await;
    assert!(binary["assumptions"].to_string().contains("overflow"));
    // a method the engine cannot justify says so instead of inventing a bound
    let sorted = project
        .complexity_of("com.legacy.service.Ledger.sortedIds()")
        .await;
    assert_eq!(sorted["time_complexity"], "unknown", "{sorted}");
    assert_eq!(sorted["confidence"], "low");
    // findings are a separate concern with their own endpoint
    let findings = project
        .api
        .get(&project.path("/findings"))
        .await
        .expect_status(200)
        .json();
    assert!(findings["findings"].is_array());
}

#[tokio::test]
async fn unresolved_dependencies_yield_honest_diagnostics() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("unresolved_dep").await;
    let analysis = project.analyze("8").await;

    assert_eq!(
        analysis["status"], "partial",
        "an unresolvable import is an error, not a silent success"
    );
    let diagnostics = analysis["diagnostics"].as_array().unwrap();
    let unresolved: Vec<_> = diagnostics
        .iter()
        .filter(|d| d["code"] == "unresolved_external_dependency")
        .collect();
    assert_eq!(unresolved.len(), 1, "{diagnostics:?}");
    assert!(unresolved[0]["message"]
        .as_str()
        .unwrap()
        .contains("org.apache.commons.lang3.StringUtils"));
    assert_eq!(
        unresolved[0]["source_range"]["file"],
        "src/main/java/ext/Report.java"
    );
    assert!(diagnostics.iter().any(|d| d["severity"] == "error"
        && d["source_range"]["file"] == "src/main/java/ext/Report.java"));

    // the call through the missing library is not turned into a graph edge or a complexity guess
    let graph = project.graph().await;
    let unresolved_calls = graph["unresolved_calls"].as_array().unwrap();
    assert!(
        unresolved_calls.iter().any(|c| c["name"] == "capitalize"),
        "{unresolved_calls:?}"
    );
    let title = project.complexity_of("ext.Report.title(String)").await;
    assert_eq!(title["time_complexity"], "unknown");
    // code unrelated to the missing library is still analysed
    let plain = project.complexity_of("ext.Report.plain(int)").await;
    assert_eq!(plain["time_complexity"], "O(1)");
}

fn single(path: &str, source: &str) -> Vec<(String, Vec<u8>)> {
    vec![(path.to_owned(), source.as_bytes().to_vec())]
}

#[tokio::test]
async fn source_levels_are_honoured_not_guessed() {
    let server = TestServer::start().await;
    let api = server.client().await;

    // Java 8 syntax is fine at 8, 17 and 21
    let legacy = api.create_from_fixture("legacy_java8").await;
    for level in ["8", "17", "21"] {
        let analysis = legacy.analyze(level).await;
        assert_eq!(
            analysis["status"], "completed",
            "level {level}: {}",
            analysis["diagnostics"]
        );
        assert_eq!(
            analysis["methods"].as_array().unwrap().len(),
            18,
            "level {level}"
        );
    }

    // later syntax is an error at 8 and accepted at 17 - the level is not silently raised
    let record = api
        .create_from_entries(
            "rec.zip",
            &single("src/R.java", "public record R(int a) { }\n"),
        )
        .await;
    assert_eq!(record.analyze("8").await["status"], "partial");
    assert_eq!(record.analyze("17").await["status"], "completed");
    let var = api
        .create_from_entries(
            "var.zip",
            &single(
                "src/V.java",
                "public class V { int f() { var x = 1; return x; } }\n",
            ),
        )
        .await;
    assert_eq!(var.analyze("8").await["status"], "partial");
    assert_eq!(var.analyze("11").await["status"], "completed");
    let block = "public class T { String s = \"\"\"\n    hi\n    \"\"\"; }\n";
    let text_block = api
        .create_from_entries("tb.zip", &single("src/T.java", block))
        .await;
    assert_eq!(text_block.analyze("11").await["status"], "partial");
    assert_eq!(text_block.analyze("17").await["status"], "completed");
    let switch =
        "public class S { int f(int d) { return switch (d) { case 1 -> 10; default -> 0; }; } }\n";
    let switch_project = api
        .create_from_entries("sw.zip", &single("src/S.java", switch))
        .await;
    assert_eq!(switch_project.analyze("8").await["status"], "partial");
    assert_eq!(switch_project.analyze("21").await["status"], "completed");

    // Java 1.2 - 1.7 cannot be honoured by the bundled JDT: explicit, identical, predictable rejection
    for level in [
        "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "7", "5", "99", "abc",
    ] {
        let error = legacy
            .api
            .post_json(
                &legacy.path(&format!("/analyze?source_level={level}")),
                &Value::Null,
            )
            .await
            .expect_error(400, "unsupported_source_level");
        assert!(
            error["message"].as_str().unwrap().contains("8, 11, 17, 21"),
            "{error}"
        );
        legacy
            .api
            .get(&legacy.path(&format!("/optimizations?source_level={level}")))
            .await
            .expect_error(400, "unsupported_source_level");
    }
    assert_eq!(legacy.revision().await, 1);
}

#[tokio::test]
async fn organizer_monolith_workflow_still_works_end_to_end() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let source = organizer_source();
    assert!(
        source.len() > 25_000,
        "this is the real ~1000 line monolith"
    );
    let project = api.create_project("Organizer.java", source.clone()).await;
    assert_eq!(project.kind, "single_file");

    let analysis = project.analyze_single_file().await;
    assert_eq!(
        analysis["status"], "completed",
        "{}",
        analysis["diagnostics"]
    );
    let graph = project.graph().await;
    assert_eq!(graph["scope"], "compilation_unit");
    assert!(graph["nodes"].as_array().unwrap().len() > 40);

    let expectations = [
        ("Organizer.binarySearch(int[],int)", "O(log n)"),
        (
            "Organizer.linkTasksToContacts(List<Task>,List<Contact>,Map<Integer, String>)",
            "O(n*m)",
        ),
        (
            "Organizer.linkTasksIndexed(List<Task>,List<Contact>,Map<Integer, String>)",
            "O(n + m)",
        ),
        ("Organizer.multiply(long[][],long[][],int)", "O(n^3)"),
        ("Organizer.gcd(int,int)", "unknown"),
    ];
    for (method, time) in expectations {
        assert_eq!(
            project.complexity_of(method).await["time_complexity"],
            time,
            "{method}"
        );
    }
    // the nested scan is found, rewritten into what the case's hand-written version looks like, and re-analysed
    let method = "Organizer.linkTasksToContacts(List<Task>,List<Contact>,Map<Integer, String>)";
    let proposals = project.optimizations("21").await;
    assert_eq!(
        proposals["proposals"].as_array().unwrap().len(),
        1,
        "only the genuine keyed scan is proposed"
    );
    let applied = project.optimize("21", method).await;
    assert_eq!(applied["revision"], 2);
    assert_eq!(applied["method_complexity_before"], "O(n*m)");
    assert_eq!(applied["method_complexity_after"], "O(n + m)");
    let now = project.source("Organizer.java", None).await;
    assert!(now.contains("Map<Integer, Contact> contactListById = new HashMap<>();"));
    // only this method changed: a handful of lines, reported by the revision diff
    let diff = project.revision_diff(1, 2).await;
    let files = diff["files"].as_array().unwrap();
    assert_eq!(files.len(), 1);
    assert!(
        files[0]["additions"].as_u64().unwrap() <= 12
            && files[0]["deletions"].as_u64().unwrap() <= 12,
        "{}",
        files[0]["unified_diff"]
    );
    let patched = project.source("Organizer.java", Some(1)).await;
    assert_eq!(
        patched.as_bytes(),
        source.as_slice(),
        "revision 1 stays untouched"
    );
    assert_eq!(
        project.complexity_of(method).await["time_complexity"],
        "O(n + m)"
    );
}
