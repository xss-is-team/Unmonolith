//! Decomposition of the demo monolith into one file per member type, driven over HTTP against the real parser.
//! Static only (no Docker); the executed-behaviour check lives in `docker_e2e.rs`.

mod common;

use common::*;
use serde_json::{json, Value};

async fn candidates(project: &Project) -> Vec<Value> {
    project
        .api
        .get(&project.path("/decomposition"))
        .await
        .expect_status(200)
        .json()["candidates"]
        .as_array()
        .unwrap()
        .clone()
}

fn request(revision: u64, classes: &[&str]) -> Value {
    json!({"base_revision": revision, "classes": classes})
}

#[tokio::test]
async fn the_demo_monolith_is_split_into_files_and_the_project_becomes_multi_file() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api
        .create_project("Main.java", demo_source().into_bytes())
        .await;
    assert_eq!(project.kind, "single_file");
    project.analyze_single_file().await;

    let found = candidates(&project).await;
    assert_eq!(found.len(), 16, "{found:?}");
    assert!(found.iter().all(|c| c["extractable"] == true), "{found:?}");
    let optimizer = found
        .iter()
        .find(|c| c["id"] == "Main.DataOptimizer")
        .unwrap();
    assert_eq!(optimizer["new_file"], "DataOptimizer.java");
    assert!(optimizer["depends_on"]
        .as_array()
        .unwrap()
        .contains(&json!("Main.Item")));

    // preview: nothing is stored
    let preview = api
        .post_json(&project.path("/decomposition/preview"), &request(1, &[]))
        .await
        .expect_status(200)
        .json();
    assert_eq!(preview["validation"], "valid");
    assert_eq!(preview["added_files"].as_array().unwrap().len(), 16);
    assert_eq!(preview["changed_files"], json!(["Main.java"]));
    assert_eq!(preview["host_lines"]["Main.java"]["before"], 819);
    assert!(
        preview["host_lines"]["Main.java"]["after"]
            .as_u64()
            .unwrap()
            < 260
    );
    let statuses: Vec<&str> = preview["diff"]
        .as_array()
        .unwrap()
        .iter()
        .map(|d| d["status"].as_str().unwrap())
        .collect();
    assert_eq!(statuses.iter().filter(|s| **s == "added").count(), 16);
    assert_eq!(statuses.iter().filter(|s| **s == "modified").count(), 1);
    assert_eq!(project.revision().await, 1);
    assert_eq!(project.info().await["kind"], "single_file");

    // refusals
    let sha = preview["preview_sha256"].clone();
    let apply = |base: u64, sha: &Value, classes: &[&str]| {
        let body = json!({"base_revision": base, "preview_sha256": sha, "classes": classes});
        let path = project.path("/decomposition/apply");
        let api = api.clone();
        async move { api.post_json(&path, &body).await }
    };
    apply(1, &json!("0".repeat(64)), &[])
        .await
        .expect_error(409, "preview_mismatch");
    apply(2, &sha, &[])
        .await
        .expect_error(409, "revision_conflict");
    api.post_json(
        &project.path("/decomposition/preview"),
        &request(1, &["Main.Nope"]),
    )
    .await
    .expect_error(404, "class_not_found");
    api.post_json(
        &project.path("/decomposition/preview"),
        &request(1, &["Main.Order"]),
    )
    .await
    .expect_error(422, "dependency_not_selected");
    api.post_json(&project.path("/decomposition/preview"), &request(2, &[]))
        .await
        .expect_error(409, "revision_conflict");
    assert_eq!(
        project.revision().await,
        1,
        "refused requests change nothing"
    );

    // apply
    let applied = apply(1, &sha, &[]).await.expect_status(200).json();
    assert_eq!(applied["revision"], 2);
    assert_eq!(applied["project_kind"], "archive");
    assert_eq!(applied["file_count"], 17);
    assert_eq!(applied["analysis_status"], "completed");
    let info = project.info().await;
    assert_eq!(
        (
            info["kind"].as_str(),
            info["file_count"].as_u64(),
            info["revision"].as_u64()
        ),
        (Some("archive"), Some(17), Some(2))
    );

    // the new files and the shrunken host are the current revision; the original is still readable
    let paths = project.file_paths().await;
    assert!(
        paths.contains(&"DataOptimizer.java".to_owned()) && paths.contains(&"Main.java".to_owned()),
        "{paths:?}"
    );
    assert_eq!(project.source("Main.java", Some(1)).await, demo_source());
    let host = project.source("Main.java", Some(2)).await;
    assert!(!host.contains("class DataOptimizer") && host.contains("public static void main"));
    let moved = project.source("DataOptimizer.java", Some(2)).await;
    assert!(
        moved.contains("public class DataOptimizer {")
            && !moved.contains("static class DataOptimizer"),
        "{moved}"
    );
    assert!(
        moved.contains("import java.util."),
        "the moved type keeps the imports it needs"
    );

    // the split project is analysed as a project: its methods now live in their own files
    let methods = project.methods().await;
    let ids = method_ids(&methods);
    assert!(
        ids.contains("DataOptimizer.mergeDuplicateItemsQuadratic(List<Item>)"),
        "{ids}"
    );
    let complexity = project
        .complexity_of("DataOptimizer.mergeDuplicateItemsQuadratic(List<Item>)")
        .await;
    assert_eq!(complexity["time_complexity"], "O(n^2)", "{complexity}");

    // single-file routes are closed for the converted project, the multi-file ones work
    api.put_json(
        &project.path("/source"),
        &json!({"source": "x", "expected_revision": 2}),
    )
    .await
    .expect_error(409, "multi_file_project");
    // a second run has nothing left to move except what stayed nested (nothing here)
    api.post_json(&project.path("/decomposition/preview"), &request(2, &[]))
        .await
        .expect_error(422, "nothing_to_extract");

    // rollback restores the single file as a new revision
    api.post_json(
        &project.path("/rollback"),
        &json!({"base_revision": 2, "to_revision": 1}),
    )
    .await
    .expect_status(200);
    assert_eq!(project.file_paths().await, vec!["Main.java".to_owned()]);
    assert_eq!(project.source("Main.java", None).await, demo_source());
}

#[tokio::test]
async fn a_subset_closed_under_dependencies_moves_alone_and_the_rest_stays_nested() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api
        .create_project("Main.java", demo_source().into_bytes())
        .await;
    project.analyze_single_file().await;
    let classes = [
        "Main.AppException",
        "Main.ValidationException",
        "Main.EntityNotFoundException",
    ];
    let preview = api
        .post_json(
            &project.path("/decomposition/preview"),
            &request(1, &classes),
        )
        .await
        .expect_status(200)
        .json();
    assert_eq!(preview["added_files"].as_array().unwrap().len(), 3);
    let applied = api
        .post_json(
            &project.path("/decomposition/apply"),
            &json!({"base_revision": 1, "classes": classes, "preview_sha256": preview["preview_sha256"]}),
        )
        .await
        .expect_status(200)
        .json();
    assert_eq!(applied["file_count"], 4);
    let host = project.source("Main.java", None).await;
    assert!(
        host.contains("class Item") && !host.contains("class AppException"),
        "the others stay nested"
    );
    // what is left can be split in a second step
    let second = candidates(&project).await;
    assert_eq!(second.len(), 13);
}

#[tokio::test]
async fn nothing_is_extracted_from_code_that_would_not_survive_the_move() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let source = "public class Host {\n    private static int secret = 1;\n    static class Peek { int look() { return Host.secret; } }\n"
        .to_owned()
        + "    public static void main(String[] a) { System.out.println(new Peek().look()); }\n}\n";
    let project = api.create_project("Host.java", source.into_bytes()).await;
    project.analyze_single_file().await;
    let found = candidates(&project).await;
    assert_eq!(found[0]["extractable"], false);
    assert!(
        found[0]["not_extractable_reasons"]
            .to_string()
            .contains("private member 'secret'"),
        "{found:?}"
    );
    let error = api
        .post_json(
            &project.path("/decomposition/preview"),
            &request(1, &["Host.Peek"]),
        )
        .await
        .expect_error(422, "not_extractable");
    assert!(
        error["message"].as_str().unwrap().contains("secret"),
        "{error}"
    );
    api.post_json(&project.path("/decomposition/preview"), &request(1, &[]))
        .await
        .expect_error(422, "nothing_to_extract");
}

#[tokio::test]
async fn projects_of_other_sessions_cannot_be_decomposed() {
    let server = TestServer::start().await;
    let owner = server.client().await;
    let stranger = server.client().await;
    let project = owner
        .create_project("Main.java", demo_source().into_bytes())
        .await;
    project.analyze_single_file().await;
    let path = project.path("/decomposition");
    stranger
        .get(&path)
        .await
        .expect_error(404, "project_not_found");
    stranger
        .post_json(&format!("{path}/preview"), &request(1, &[]))
        .await
        .expect_error(404, "project_not_found");
}

const REPORT: &str = r#"import java.util.*;

public class Report {
    private static final String SEP = ", ";

    static class Row {
        final String name;
        final int qty;
        Row(String name, int qty) { this.name = name; this.qty = qty; }
    }

    static Row parseRow(String line) {
        String[] p = line.split(":");
        return new Row(p[0].trim(), toQty(p[1]));
    }

    private static int toQty(String s) {
        return Integer.parseInt(s.trim());
    }

    static List<Row> parseAll(List<String> lines) {
        List<Row> rows = new ArrayList<>();
        for (String l : lines) {
            rows.add(parseRow(l));
        }
        return rows;
    }

    static String formatRow(Row r) {
        return r.name + "=" + r.qty;
    }

    static String formatAll(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(formatRow(r));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(formatAll(parseAll(List.of("a: 1", "b: 2"))));
    }
}
"#;

#[tokio::test]
async fn a_class_of_static_methods_is_split_into_responsibility_classes() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api
        .create_project("Report.java", REPORT.as_bytes().to_vec())
        .await;
    project.analyze_single_file().await;

    let listing = api
        .get(&project.path("/decomposition"))
        .await
        .expect_status(200)
        .json();
    let groups = listing["method_groups"].as_array().unwrap();
    let names: Vec<&str> = groups.iter().map(|g| g["name"].as_str().unwrap()).collect();
    assert_eq!(names, vec!["Parsing", "Formatting"], "{listing}");
    assert!(groups.iter().all(|g| g["extractable"] == true), "{listing}");
    assert_eq!(
        groups[0]["moves_along"][0]["name"], "toQty",
        "the private helper travels with the group"
    );

    // member types and method groups are separate steps
    api.post_json(
        &project.path("/decomposition/preview"),
        &json!({"base_revision": 1, "classes": ["Report.Row"], "groups": [{"id": "Report#Parsing"}]}),
    )
    .await
    .expect_error(400, "invalid_request");
    api.post_json(
        &project.path("/decomposition/preview"),
        &json!({"base_revision": 1, "groups": [{"id": "Report#Nope"}]}),
    )
    .await
    .expect_error(404, "class_not_found");

    let body = json!({"base_revision": 1, "groups": [{"id": "Report#Parsing"}, {"id": "Report#Formatting", "name": "Printing"}]});
    let preview = api
        .post_json(&project.path("/decomposition/preview"), &body)
        .await
        .expect_status(200)
        .json();
    assert_eq!(
        preview["added_files"],
        json!(["Parsing.java", "Printing.java"])
    );
    assert_eq!(preview["changed_files"], json!(["Report.java"]));
    assert_eq!(preview["extracted"][1]["kind"], "method_group");

    let mut apply = body.clone();
    apply["preview_sha256"] = preview["preview_sha256"].clone();
    let applied = api
        .post_json(&project.path("/decomposition/apply"), &apply)
        .await
        .expect_status(200)
        .json();
    assert_eq!(applied["project_kind"], "archive");
    assert_eq!(applied["file_count"], 3);
    let host = project.source("Report.java", None).await;
    assert!(
        host.contains("Printing.formatAll(Parsing.parseAll("),
        "{host}"
    );
    assert!(!host.contains("static Row parseRow"));
    let parsing = project.source("Parsing.java", None).await;
    assert!(
        parsing.contains("static List<Report.Row> parseAll") && parsing.contains("toQty"),
        "{parsing}"
    );
    let ids = method_ids(&project.methods().await);
    assert!(
        ids.contains("Parsing.parseAll(List<String>)")
            && ids.contains("Printing.formatRow(Report.Row)")
            && !ids.contains("Report.parseAll(List<String>)"),
        "{ids}"
    );
}
