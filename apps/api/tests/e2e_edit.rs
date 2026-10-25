//! Automatic optimization, CFG editing and reliability, driven over HTTP against the real parser.
//! Everything here is static (no Docker); executed-behaviour checks live in `docker_e2e.rs`.

mod common;

use common::*;
use serde_json::{json, Value};

const LINK: &str = "shop.service.OrderLinker.link(List<Order>,List<Customer>,Map<Integer, String>)";
const DESCRIBE: &str = "shop.service.OrderLinker.describeAll(List<Order>,List<Customer>)";
const LINKER: &str = "src/main/java/shop/service/OrderLinker.java";
const PRICE: &str = "calc.Pricing.price(int,int,int)";
const PRICING: &str = "src/main/java/calc/Pricing.java";

fn ids(proposal: &Value) -> Vec<String> {
    proposal["assumptions"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_str().unwrap().to_owned())
        .collect()
}

#[tokio::test]
async fn safe_nested_lookups_are_discovered_with_full_evidence() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;

    let all = project.optimizations("8").await;
    assert_eq!(all["rule"], "nested_scan_to_hashmap");
    assert_eq!(all["proposals"].as_array().unwrap().len(), 2);

    let first = project.proposal_for("8", LINK).await;
    assert_eq!(first["applicability"], "applicable");
    assert_eq!(
        first["shape"], "first_match",
        "break after the first match keeps the first duplicate"
    );
    assert_eq!(first["file"], LINKER);
    assert_eq!(first["affected_files"], json!([LINKER]));
    assert_eq!(first["key"]["element_expression"], "customer.getId()");
    assert_eq!(first["key"]["lookup_expression"], "order.getCustomerId()");
    assert_eq!(first["key"]["type"], "int");
    for precondition in first["preconditions"].as_array().unwrap() {
        assert_eq!(precondition["status"], "satisfied", "{precondition}");
    }
    let names: Vec<_> = first["preconditions"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p["id"].as_str().unwrap())
        .collect();
    for required in [
        "key_equality",
        "key_purity",
        "collection_not_modified",
        "control_flow",
        "lookup_shape",
    ] {
        assert!(
            names.contains(&required),
            "{required} missing from {names:?}"
        );
    }
    assert_eq!(
        ids(&first),
        vec!["null_and_exception_timing", "single_threaded"]
    );
    assert_eq!(first["complexity"]["method_before"], "O(n*m)");
    assert_eq!(first["complexity"]["scan_after"], "O(n + m)");
    assert!(
        first["complexity"]["note"]
            .as_str()
            .unwrap()
            .contains("expectation"),
        "HashMap is not presented as a worst-case bound"
    );

    let second = project.proposal_for("8", DESCRIBE).await;
    assert_eq!(second["applicability"], "applicable");
    assert_eq!(
        second["shape"], "all_matches",
        "no break: every duplicate match is processed in order"
    );
    assert_eq!(second["complexity"]["scan_after"], "O(n + m + k)");
    assert_ne!(first["id"], second["id"]);
}

#[tokio::test]
async fn preview_shows_the_exact_diff_and_apply_changes_only_the_target_file() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    project.analyze("8").await;
    let before_all = project.file_paths().await;
    let mut before = std::collections::BTreeMap::new();
    for path in &before_all {
        before.insert(path.clone(), project.source(path, None).await);
    }

    let proposal = project.proposal_for("8", LINK).await;
    let preview = project
        .preview_optimization(proposal["id"].as_str().unwrap(), 1, "8")
        .await
        .expect_status(200)
        .json();
    assert_eq!(preview["validation"], "valid");
    let diff = preview["diff"].as_array().unwrap();
    assert_eq!(diff.len(), 1);
    assert_eq!(diff[0]["path"], LINKER);
    let unified = diff[0]["unified_diff"].as_str().unwrap();
    for expected in [
        "+import java.util.HashMap;",
        "+        Map<Integer, Customer> customersById = new HashMap<>();",
        "+        for (Customer customer : customers) {",
        "+            customersById.putIfAbsent(customer.getId(), customer);",
        "+            Customer customer = customersById.get(order.getCustomerId());",
        "+            if (customer != null) {",
        "-            for (Customer customer : customers) {",
        "-                if (customer.getId() == order.getCustomerId()) {",
        "-                    break;",
    ] {
        assert!(
            unified.lines().any(|l| l == expected),
            "diff lacks {expected:?}:\n{unified}"
        );
    }
    assert_eq!(preview["method_complexity_before"], "O(n*m)");
    assert_eq!(preview["method_complexity_after"], "O(n + m)");
    // previewing stored nothing
    assert_eq!(project.revision().await, 1);
    assert_eq!(project.source(LINKER, None).await, before[LINKER]);

    let applied = project
        .apply_optimization(&proposal, 1, "8", &preview["preview_sha256"])
        .await
        .expect_status(200)
        .json();
    assert_eq!(applied["revision"], 2);
    assert_eq!(applied["analysis_status"], "completed");
    assert_eq!(
        applied["diff"], preview["diff"],
        "apply commits exactly what was previewed"
    );

    // the right file changed, every other file is byte-identical, revision 1 is untouched
    for (path, original) in &before {
        let now = project.source(path, Some(2)).await;
        if path == LINKER {
            assert_ne!(&now, original);
            assert!(now.contains("customersById.putIfAbsent(customer.getId(), customer);"));
            assert!(
                !now.contains("break;"),
                "the break moved into the index (first match wins)"
            );
        } else {
            assert_eq!(&now, original, "{path} must not change");
        }
        assert_eq!(&project.source(path, Some(1)).await, original);
    }
    let compare = project.revision_diff(1, 2).await;
    assert_eq!(compare["files"].as_array().unwrap().len(), 1);
    assert_eq!(compare["files"][0]["path"], LINKER);

    // revision 2 was re-analysed: same method, better static estimate, consistent with the complexity API
    let after = project.complexity_of(LINK).await;
    assert_eq!(after["time_complexity"], "O(n + m)");
    assert_eq!(applied["method_complexity_after"], after["time_complexity"]);
    assert_eq!(project.complexity().await["revision"], 2);
    // the optimized method no longer has a nested-scan proposal
    let remaining = project.optimizations("8").await;
    assert!(remaining["proposals"]
        .as_array()
        .unwrap()
        .iter()
        .all(|p| p["method_id"] != LINK));
}

#[tokio::test]
async fn all_matches_rewrite_keeps_duplicates_and_order_in_the_source_form() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    let applied = project.optimize("8", DESCRIBE).await;
    let now = project.source(LINKER, None).await;
    assert!(
        now.contains("Map<Integer, List<Customer>> customersById = new HashMap<>();"),
        "{now}"
    );
    assert!(
        now.contains(
            "computeIfAbsent(customer.getId(), ignored -> new ArrayList<>()).add(customer);"
        ),
        "{now}"
    );
    assert!(
        now.contains("List<Customer> customerMatches = customersById.get(order.getCustomerId());"),
        "{now}"
    );
    assert!(
        now.contains("for (Customer customer : customerMatches)"),
        "{now}"
    );
    // the first-match method in the same file is untouched by this proposal
    assert!(now.contains("owners.put(order.getId(), customer.getName());"));
    assert!(now.contains("break;"));
    // the lines list is still built in the loop body in the same order
    assert!(now.contains(
        "lines.add(order.getId() + \":\" + customer.getName() + \":\" + order.getCents());"
    ));
    assert_eq!(applied["revision"], 2);
}

#[tokio::test]
async fn apply_is_guarded_by_revision_fingerprint_and_acknowledgement() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    let original = project.source(LINKER, None).await;
    let proposal = project.proposal_for("8", LINK).await;
    let id = proposal["id"].as_str().unwrap();
    let preview = project
        .preview_optimization(id, 1, "8")
        .await
        .expect_status(200)
        .json();
    let sha = preview["preview_sha256"].clone();

    project
        .preview_optimization(id, 5, "8")
        .await
        .expect_error(409, "revision_conflict");
    project
        .apply_optimization(&proposal, 5, "8", &sha)
        .await
        .expect_error(409, "revision_conflict");
    project
        .apply_optimization(&proposal, 1, "8", &json!("0".repeat(64)))
        .await
        .expect_error(409, "preview_mismatch");
    let path = format!("/optimizations/{id}/apply");
    project
        .api
        .post_json(&project.path(&path), &json!({"base_revision": 1, "source_level": "8", "preview_sha256": sha, "acknowledged_assumptions": ["single_threaded"]}))
        .await
        .expect_error(422, "assumptions_not_acknowledged");
    project
        .api
        .post_json(&project.path("/optimizations/opt_0000000000000000/apply"), &json!({"base_revision": 1, "preview_sha256": sha, "acknowledged_assumptions": ["null_and_exception_timing", "single_threaded"]}))
        .await
        .expect_error(404, "proposal_not_found");
    project
        .api
        .post_json(
            &project.path("/optimizations/not-an-id/apply"),
            &json!({"base_revision": 1, "preview_sha256": sha, "acknowledged_assumptions": []}),
        )
        .await
        .expect_error(400, "invalid_request");
    assert_eq!(
        project.revision().await,
        1,
        "nothing was applied by any rejected call"
    );
    assert_eq!(project.source(LINKER, None).await, original);

    // a proposal of an old revision cannot be replayed after the project moved on
    project
        .apply_optimization(&proposal, 1, "8", &sha)
        .await
        .expect_status(200);
    project
        .apply_optimization(&proposal, 1, "8", &sha)
        .await
        .expect_error(409, "revision_conflict");
    project
        .apply_optimization(&proposal, 2, "8", &sha)
        .await
        .expect_error(404, "proposal_not_found");
}

#[tokio::test]
async fn rollback_restores_an_earlier_revision_as_a_new_one() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    project.analyze("8").await;
    let original = project.source(LINKER, None).await;
    project.optimize("8", LINK).await;
    assert_ne!(project.source(LINKER, None).await, original);

    let rollback = |base: u64, to: u64| {
        let project = project.clone();
        async move {
            project
                .api
                .post_json(
                    &project.path("/rollback"),
                    &json!({"base_revision": base, "to_revision": to}),
                )
                .await
        }
    };
    rollback(1, 1).await.expect_error(409, "revision_conflict");
    rollback(2, 2).await.expect_error(400, "invalid_request");
    rollback(2, 0).await.expect_error(400, "invalid_request");
    let restored = rollback(2, 1).await.expect_status(200).json();
    assert_eq!(
        restored["revision"], 3,
        "history is kept, the restore is a new revision"
    );
    assert_eq!(project.source(LINKER, None).await, original);
    assert_eq!(project.complexity().await["revision"], 3);
    assert_eq!(
        project.complexity_of(LINK).await["time_complexity"],
        "O(n*m)"
    );
    assert_eq!(
        project.revision_diff(1, 3).await["files"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    assert_eq!(
        project.revision_diff(2, 3).await["files"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
}

#[tokio::test]
async fn unsafe_lookups_are_refused_with_the_violated_precondition() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("unsafe_lookups").await;
    let rules = "src/main/java/risk/Rules.java";
    let original = project.source(rules, None).await;

    let all = project.optimizations("8").await;
    let proposals = all["proposals"].as_array().unwrap();
    assert_eq!(
        proposals.len(),
        5,
        "every unsafe scan is reported, none is silently skipped"
    );
    let expectations = [
        (
            "risk.Rules.matchByScore(List<Item>,List<Item>,Map<Integer, Item>)",
            "key_equality",
        ),
        (
            "risk.Rules.matchByCodeIdentity(List<Item>,List<Item>,Map<Integer, Item>)",
            "key_equality",
        ),
        (
            "risk.Rules.matchWhileGrowing(List<Item>,List<Item>)",
            "collection_not_modified",
        ),
        (
            "risk.Rules.matchWithSideEffect(List<Item>,List<Item>,Map<Integer, Item>)",
            "key_purity",
        ),
        (
            "risk.Rules.matchAndCount(List<Item>,List<Item>)",
            "lookup_shape",
        ),
    ];
    for (method, violated) in expectations {
        let proposal = proposals
            .iter()
            .find(|p| p["method_id"] == method)
            .unwrap_or_else(|| panic!("no proposal for {method}"));
        assert_eq!(proposal["applicability"], "not_applicable", "{method}");
        assert_eq!(
            proposal["assumptions"],
            json!([]),
            "nothing to acknowledge for an inapplicable proposal"
        );
        assert!(!proposal["not_applicable_reasons"]
            .as_array()
            .unwrap()
            .is_empty());
        let precondition = proposal["preconditions"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["id"] == violated)
            .unwrap();
        assert_eq!(
            precondition["status"], "violated",
            "{method}: {precondition}"
        );

        let id = proposal["id"].as_str().unwrap();
        project
            .preview_optimization(id, 1, "8")
            .await
            .expect_error(422, "optimization_not_applicable");
        project
            .api
            .post_json(
                &project.path(&format!("/optimizations/{id}/apply")),
                &json!({"base_revision": 1, "source_level": "8", "preview_sha256": "x", "acknowledged_assumptions": ["null_and_exception_timing", "single_threaded"]}),
            )
            .await
            .expect_error(422, "optimization_not_applicable");
    }
    assert_eq!(project.revision().await, 1);
    assert_eq!(
        project.source(rules, None).await,
        original,
        "no source was touched"
    );
}

// ---------------------------------------------------------------- CFG editing

async fn pricing() -> (TestServer, Project, String) {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("cfg_edit").await;
    project.analyze("8").await;
    let original = project.source(PRICING, None).await;
    (server, project, original)
}

fn line_index(source: &str, needle: &str) -> usize {
    source
        .lines()
        .position(|l| l.trim() == needle)
        .unwrap_or_else(|| panic!("{needle:?} not in\n{source}"))
}

#[tokio::test]
async fn independent_statements_move_producing_a_real_diff_and_a_valid_cfg() {
    let (_server, project, original) = pricing().await;
    let cfg = project.cfg(PRICE).await;
    let texts: Vec<_> = cfg["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|n| n["text"].as_str())
        .collect();
    assert_eq!(
        texts,
        vec![
            "int subtotal = base * qty;",
            "int tax = base / 5;",
            "int bonus = qty * 2;",
            "int total = subtotal + tax - discount;",
            "return total + bonus;"
        ]
    );
    let tax = project.cfg_node(PRICE, "int tax = base / 5;").await;
    let bonus = project.cfg_node(PRICE, "int bonus = qty * 2;").await;

    let body = Project::move_body(1, "8", PRICE, &bonus, &tax, "before");
    let preview = project.cfg_preview(&body).await.expect_status(200).json();
    let unified = preview["diff"][0]["unified_diff"].as_str().unwrap();
    assert!(
        unified
            .lines()
            .any(|l| l == "+        int bonus = qty * 2;"),
        "{unified}"
    );
    assert!(
        unified
            .lines()
            .any(|l| l == "-        int bonus = qty * 2;"),
        "{unified}"
    );
    assert_eq!(preview["operation"]["moved"]["text"], "intbonus=qty*2;");
    assert_eq!(
        preview["operation"]["jumped_over"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(preview["operation"]["jumped_over"][0]["independent"], true);
    assert_eq!(preview["diff"][0]["additions"], 1);
    assert_eq!(preview["diff"][0]["deletions"], 1);
    assert_eq!(project.revision().await, 1, "preview stores nothing");
    assert_eq!(project.source(PRICING, None).await, original);

    let applied = project
        .cfg_apply(&body, &preview["preview_sha256"])
        .await
        .expect_status(200)
        .json();
    assert_eq!(applied["revision"], 2);
    assert_eq!(applied["analysis_status"], "completed");
    let moved = project.source(PRICING, None).await;
    assert!(line_index(&moved, "int bonus = qty * 2;") < line_index(&moved, "int tax = base / 5;"));
    assert!(
        line_index(&moved, "int tax = base / 5;")
            < line_index(&moved, "int total = subtotal + tax - discount;")
    );
    assert_eq!(
        moved.lines().count(),
        original.lines().count(),
        "only the order changed"
    );
    let mut a: Vec<_> = moved.lines().collect();
    let mut b: Vec<_> = original.lines().collect();
    a.sort();
    b.sort();
    assert_eq!(a, b, "the same lines, in another order");

    // reanalysed: the CFG now lists the statements in the new order, and the mapping links old to new ids
    let texts: Vec<_> = project.cfg(PRICE).await["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|n| n["text"].as_str().map(str::to_owned))
        .collect();
    assert_eq!(
        texts[..3],
        [
            "int subtotal = base * qty;",
            "int bonus = qty * 2;",
            "int tax = base / 5;"
        ]
    );
    let new_tax = project.cfg_node(PRICE, "int tax = base / 5;").await;
    let mapped = applied["node_mapping"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["old_id"] == tax.as_str())
        .unwrap();
    assert_eq!(mapped["new_id"], new_tax.as_str());
    assert_eq!(
        project.complexity_of(PRICE).await["time_complexity"],
        "O(1)"
    );

    // the mapped ids allow the inverse edit, which restores the original text exactly
    let new_bonus = project.cfg_node(PRICE, "int bonus = qty * 2;").await;
    let undo = Project::move_body(2, "8", PRICE, &new_bonus, &new_tax, "after");
    let undo_preview = project.cfg_preview(&undo).await.expect_status(200).json();
    project
        .cfg_apply(&undo, &undo_preview["preview_sha256"])
        .await
        .expect_status(200);
    assert_eq!(project.source(PRICING, None).await, original);
    assert_eq!(project.revision().await, 3);
}

#[tokio::test]
async fn a_statement_can_jump_over_several_independent_statements() {
    let (_server, project, original) = pricing().await;
    let subtotal = project.cfg_node(PRICE, "int subtotal = base * qty;").await;
    let bonus = project.cfg_node(PRICE, "int bonus = qty * 2;").await;
    let body = Project::move_body(1, "8", PRICE, &bonus, &subtotal, "before");
    let preview = project.cfg_preview(&body).await.expect_status(200).json();
    assert_eq!(
        preview["operation"]["jumped_over"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    assert_eq!(preview["operation"]["from_index"], 2);
    assert_eq!(preview["operation"]["to_index"], 0);
    project
        .cfg_apply(&body, &preview["preview_sha256"])
        .await
        .expect_status(200);
    let moved = project.source(PRICING, None).await;
    assert!(
        line_index(&moved, "int bonus = qty * 2;")
            < line_index(&moved, "int subtotal = base * qty;")
    );
    assert_ne!(moved, original);
}

#[tokio::test]
async fn dependent_moves_are_rejected_without_corrupting_the_source() {
    let (_server, project, original) = pricing().await;
    let subtotal = project.cfg_node(PRICE, "int subtotal = base * qty;").await;
    let total = project
        .cfg_node(PRICE, "int total = subtotal + tax - discount;")
        .await;
    let tax = project.cfg_node(PRICE, "int tax = base / 5;").await;

    // total reads subtotal and tax: it cannot move above them
    let body = Project::move_body(1, "8", PRICE, &total, &subtotal, "before");
    let error = project
        .cfg_preview(&body)
        .await
        .expect_error(422, "move_rejected");
    let reasons = error["details"]["reasons"].as_array().unwrap();
    assert!(
        reasons.iter().any(|r| r["code"] == "data_dependency"),
        "{error}"
    );
    let reason = reasons
        .iter()
        .find(|r| r["code"] == "data_dependency")
        .unwrap();
    assert!(
        reason["message"].as_str().unwrap().contains("subtotal")
            || reason["message"].as_str().unwrap().contains("tax")
    );
    assert!(reason["statements"].as_array().unwrap().len() >= 2);
    // the same holds for the opposite direction: subtotal cannot move below its consumer
    let body2 = Project::move_body(1, "8", PRICE, &subtotal, &total, "after");
    project
        .cfg_preview(&body2)
        .await
        .expect_error(422, "move_rejected");
    // applying a rejected edit is refused as well
    project
        .cfg_apply(&body, &json!("0".repeat(64)))
        .await
        .expect_error(422, "move_rejected");
    let _ = tax;

    assert_eq!(project.revision().await, 1);
    assert_eq!(project.source(PRICING, None).await, original);
    assert_eq!(
        project.analyze("8").await["status"],
        "completed",
        "the project still analyses cleanly"
    );
}

#[tokio::test]
async fn invalid_requests_and_stale_revisions_are_refused() {
    let (_server, project, original) = pricing().await;
    let tax = project.cfg_node(PRICE, "int tax = base / 5;").await;
    let bonus = project.cfg_node(PRICE, "int bonus = qty * 2;").await;
    let cfg = project.cfg(PRICE).await;
    let entry = cfg["entry"].as_str().unwrap().to_owned();
    let valid = Project::move_body(1, "8", PRICE, &bonus, &tax, "before");

    project
        .cfg_preview(&Project::move_body(4, "8", PRICE, &bonus, &tax, "before"))
        .await
        .expect_error(409, "revision_conflict");
    project
        .cfg_preview(&Project::move_body(
            1, "8", PRICE, "f0m9.n99", &tax, "before",
        ))
        .await
        .expect_error(422, "unsupported_node");
    project
        .cfg_preview(&Project::move_body(
            1, "8", PRICE, &bonus, "nonsense", "before",
        ))
        .await
        .expect_error(422, "unsupported_node");
    project
        .cfg_preview(&Project::move_body(1, "8", PRICE, &entry, &tax, "before"))
        .await
        .expect_error(422, "unsupported_node");
    project
        .cfg_preview(&Project::move_body(1, "8", PRICE, &bonus, &bonus, "before"))
        .await
        .expect_error(400, "invalid_operation");
    project
        .cfg_preview(&Project::move_body(
            1,
            "8",
            "calc.Pricing.nope()",
            &bonus,
            &tax,
            "before",
        ))
        .await
        .expect_error(404, "method_not_found");
    // already in place: no change
    let subtotal = project.cfg_node(PRICE, "int subtotal = base * qty;").await;
    project
        .cfg_preview(&Project::move_body(1, "8", PRICE, &tax, &subtotal, "after"))
        .await
        .expect_error(422, "no_change");
    // malformed operation
    let mut broken = valid.clone();
    broken["operation"]["position"] = json!("sideways");
    project
        .cfg_preview(&broken)
        .await
        .expect_error(400, "invalid_request");
    let mut unknown_op = valid.clone();
    unknown_op["operation"]["type"] = json!("rename_variable");
    project
        .cfg_preview(&unknown_op)
        .await
        .expect_error(400, "invalid_request");
    // wrong fingerprint and stale apply
    let preview = project.cfg_preview(&valid).await.expect_status(200).json();
    project
        .cfg_apply(&valid, &json!("f".repeat(64)))
        .await
        .expect_error(409, "preview_mismatch");
    project
        .cfg_apply(&valid, &preview["preview_sha256"])
        .await
        .expect_status(200);
    project
        .cfg_apply(&valid, &preview["preview_sha256"])
        .await
        .expect_error(409, "revision_conflict");

    assert_eq!(
        project.revision().await,
        2,
        "exactly one edit was committed"
    );
    assert_ne!(project.source(PRICING, Some(2)).await, original);
    assert_eq!(project.source(PRICING, Some(1)).await, original);
}

// ---------------------------------------------------------------- reliability

#[tokio::test]
async fn concurrent_edits_with_the_same_base_revision_commit_exactly_once() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("cfg_edit").await;
    project.analyze("8").await;
    let original = project.source(PRICING, None).await;

    let writers = 8;
    let results = futures_join((0..writers).map(|i| {
        let project = project.clone();
        let source = format!("{original}\n// writer {i}\n");
        async move { project.put_file(PRICING, &source, 1).await.status }
    }))
    .await;
    assert_eq!(
        results.iter().filter(|s| **s == 200).count(),
        1,
        "{results:?}"
    );
    assert_eq!(
        results.iter().filter(|s| **s == 409).count(),
        writers - 1,
        "{results:?}"
    );
    assert_eq!(project.revision().await, 2);

    // two identical optimizations racing on one base revision: one wins, the other sees the conflict
    let other = api.create_from_fixture("lookup_opt").await;
    other.analyze("8").await;
    let proposal = other.proposal_for("8", LINK).await;
    let preview = other
        .preview_optimization(proposal["id"].as_str().unwrap(), 1, "8")
        .await
        .expect_status(200)
        .json();
    let statuses = futures_join((0..4).map(|_| {
        let other = other.clone();
        let proposal = proposal.clone();
        let sha = preview["preview_sha256"].clone();
        async move {
            other
                .apply_optimization(&proposal, 1, "8", &sha)
                .await
                .status
        }
    }))
    .await;
    assert_eq!(
        statuses.iter().filter(|s| **s == 200).count(),
        1,
        "{statuses:?}"
    );
    assert_eq!(other.revision().await, 2);
}

/// Minimal join_all (no extra dependency): spawns every future and collects outputs in order.
async fn futures_join<F, T>(futures: impl IntoIterator<Item = F>) -> Vec<T>
where
    F: std::future::Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    let handles: Vec<_> = futures.into_iter().map(tokio::spawn).collect();
    let mut out = Vec::new();
    for handle in handles {
        out.push(handle.await.expect("task panicked"));
    }
    out
}

#[tokio::test]
async fn many_isolated_projects_run_concurrently_without_interference() {
    let server = TestServer::start().await;
    let mut clients = Vec::new();
    for _ in 0..6 {
        clients.push(server.client().await);
    }
    let tasks = futures_join(clients.into_iter().enumerate().map(|(i, api)| async move {
        let (fixture, method, expect) = match i % 3 {
            0 => ("lookup_opt", LINK, "O(n + m)"),
            1 => (
                "legacy_java8",
                "com.legacy.util.Algorithms.binarySearch(int[],int)",
                "O(log n)",
            ),
            _ => ("cfg_edit", PRICE, "O(1)"),
        };
        let project = api.create_from_fixture(fixture).await;
        project.analyze("8").await;
        if fixture == "lookup_opt" {
            project.optimize("8", method).await;
        }
        let got = project.complexity_of(method).await["time_complexity"]
            .as_str()
            .unwrap()
            .to_owned();
        (
            fixture,
            got,
            expect,
            project.files().await["files"].as_array().unwrap().len(),
        )
    }))
    .await;
    for (fixture, got, expect, files) in tasks {
        assert_eq!(got, expect, "{fixture}");
        assert_eq!(files, fixture_entries(fixture).len(), "{fixture} file set");
    }
}

#[tokio::test]
async fn foreign_sessions_cannot_edit_optimize_or_roll_back() {
    let server = TestServer::start().await;
    let owner = server.client().await;
    let intruder = server.client().await;
    let project = owner.create_from_fixture("lookup_opt").await;
    project.analyze("8").await;
    let proposal = project.proposal_for("8", LINK).await;
    let id = proposal["id"].as_str().unwrap();
    let foreign = intruder.project_handle(&project.id);

    foreign
        .api
        .get(&foreign.path("/optimizations"))
        .await
        .expect_error(404, "project_not_found");
    foreign
        .preview_optimization(id, 1, "8")
        .await
        .expect_error(404, "project_not_found");
    foreign
        .apply_optimization(&proposal, 1, "8", &json!("x"))
        .await
        .expect_error(404, "project_not_found");
    let body = Project::move_body(1, "8", LINK, "n1", "n2", "before");
    foreign
        .cfg_preview(&body)
        .await
        .expect_error(404, "project_not_found");
    foreign
        .api
        .post_json(
            &foreign.path("/rollback"),
            &json!({"base_revision": 1, "to_revision": 1}),
        )
        .await
        .expect_error(404, "project_not_found");
    foreign
        .api
        .get(&foreign.path("/revisions/1/diff/1"))
        .await
        .expect_error(404, "project_not_found");
    foreign.benchmark(&json!({"baseline_revision": 1, "candidate_revision": 1, "workloads": [{"name": "a", "stdin": "1"}]})).await.expect_error(404, "project_not_found");
    assert_eq!(project.revision().await, 1);
}

#[tokio::test]
async fn statements_can_be_inserted_and_deleted_from_the_cfg_with_validation() {
    let (_server, project, _original) = pricing().await;
    let tax = project.cfg_node(PRICE, "int tax = base / 5;").await;

    // insert: a new statement after `tax`, previewed and applied as revision 2
    let body = json!({
        "base_revision": 1, "source_level": "8", "method_id": PRICE,
        "operation": {"type": "insert_statement", "target_node_id": tax, "position": "after", "code": "int audit = tax * 2;"},
    });
    let preview = project.cfg_preview(&body).await.expect_status(200).json();
    let unified = preview["diff"][0]["unified_diff"].as_str().unwrap();
    assert!(
        unified.contains("+        int audit = tax * 2;"),
        "{unified}"
    );
    assert_eq!(preview["operation"]["type"], "insert_statement");
    project
        .cfg_apply(&body, &preview["preview_sha256"])
        .await
        .expect_status(200);
    assert_eq!(project.revision().await, 2);
    assert!(project
        .source(PRICING, None)
        .await
        .contains("int audit = tax * 2;"));

    // delete the statement that was just inserted (nothing uses it): applies
    project.analyze("8").await;
    let audit = project.cfg_node(PRICE, "int audit = tax * 2;").await;
    let delete = json!({
        "base_revision": 2, "source_level": "8", "method_id": PRICE,
        "operation": {"type": "delete_statement", "node_id": audit},
    });
    let delete_preview = project.cfg_preview(&delete).await.expect_status(200).json();
    project
        .cfg_apply(&delete, &delete_preview["preview_sha256"])
        .await
        .expect_status(200);
    assert!(!project.source(PRICING, None).await.contains("int audit"));

    // refusals: broken code, a deletion that breaks the method, an unknown node, a type error
    project.analyze("8").await;
    let subtotal = project.cfg_node(PRICE, "int subtotal = base * qty;").await;
    let bad = |code: &str| {
        json!({"base_revision": 3, "source_level": "8", "method_id": PRICE,
            "operation": {"type": "insert_statement", "target_node_id": subtotal, "position": "before", "code": code}})
    };
    project
        .cfg_preview(&bad("int = ;"))
        .await
        .expect_error(400, "invalid_operation");
    project
        .cfg_preview(&bad("String s = subtotal;"))
        .await
        .expect_error(422, "transformation_invalid");
    let remove_used = json!({"base_revision": 3, "source_level": "8", "method_id": PRICE,
        "operation": {"type": "delete_statement", "node_id": subtotal}});
    project
        .cfg_preview(&remove_used)
        .await
        .expect_error(422, "transformation_invalid");
    let unknown = json!({"base_revision": 3, "source_level": "8", "method_id": PRICE,
        "operation": {"type": "delete_statement", "node_id": "nope"}});
    project
        .cfg_preview(&unknown)
        .await
        .expect_error(422, "unsupported_node");
    assert_eq!(project.revision().await, 3, "refused edits change nothing");
}
