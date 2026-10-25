//! Advisory warnings attached to a verification report.
//!
//! A passing verification only covers the scenarios that were run. These checks flag the cases where a green
//! result says little: no scenario provides input, the two revisions have identical method bodies, or a changed
//! (or newly appearing) method cannot be reached from `main` in the static call graph. They only read the JSON artifacts the Java
//! parser already stored per revision (method bodies and `project_graph`); the API never parses Java.

use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use utoipa::ToSchema;

use crate::runner::VerifyTestCase;

/// How many method ids a warning lists before it summarizes the rest.
const LISTED_METHODS: usize = 5;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
pub struct VerifyWarning {
    /// Stable machine-readable code: `empty_scenarios`, `no_method_changes`, `change_not_reached`,
    /// `reachability_unknown`.
    pub code: String,
    pub message: String,
}

fn warning(code: &str, message: String) -> VerifyWarning {
    VerifyWarning {
        code: code.to_owned(),
        message,
    }
}

/// Warnings that depend only on the supplied scenarios.
pub fn scenario_warnings(cases: &[VerifyTestCase]) -> Vec<VerifyWarning> {
    if cases.iter().all(|case| case.stdin.trim().is_empty()) {
        return vec![warning(
            "empty_scenarios",
            "Every scenario has empty stdin, so a program that reads input is only exercised on its no-input path. \
             Add scenarios with realistic input before relying on this result."
                .to_owned(),
        )];
    }
    Vec::new()
}

/// Warnings that compare the stored analysis artifacts of the two revisions.
pub fn change_warnings(baseline: Option<&Value>, candidate: Option<&Value>) -> Vec<VerifyWarning> {
    let (Some(baseline), Some(candidate)) = (baseline, candidate) else {
        return vec![warning(
            "reachability_unknown",
            "Both revisions must be analyzed to check whether the changed code is reachable from main; \
             analyze them and verify again."
                .to_owned(),
        )];
    };
    let before = method_fingerprints(baseline);
    let after = method_fingerprints(candidate);
    // a method counts as changed when its declaration differs, and as affected when it is new in the candidate and
    // matches no baseline declaration (code that moved to another type or file gets a new id after a decomposition)
    let moved: Vec<Value> = before.values().map(without_id).collect();
    let changed: Vec<&String> = after
        .iter()
        .filter(|(id, body)| match before.get(*id) {
            Some(old) => old != *body,
            // new id: unchanged code that merely moved (another type or file) is not a change
            None => !moved.contains(&without_id(body)),
        })
        .map(|(id, _)| id)
        .collect();
    if changed.is_empty() {
        return vec![warning(
            "no_method_changes",
            "No method differs between the two revisions (comments and formatting are ignored), so this run \
             compares a program with itself."
                .to_owned(),
        )];
    }
    let mut reachable = BTreeSet::new();
    let mut graphs = 0;
    for artifact in [baseline, candidate] {
        if let Some(set) = reachable_methods(artifact) {
            reachable.extend(set);
            graphs += 1;
        }
    }
    if graphs < 2 {
        return vec![warning(
            "reachability_unknown",
            "The project graph is unavailable for at least one revision (no main method found or the parser \
             could not build it), so reachability of the changed code was not checked."
                .to_owned(),
        )];
    }
    let unreached: Vec<&str> = changed
        .iter()
        .filter(|id| !reachable.contains(id.as_str()))
        .map(|id| id.as_str())
        .collect();
    if unreached.is_empty() {
        return Vec::new();
    }
    let listed = unreached
        .iter()
        .take(LISTED_METHODS)
        .copied()
        .collect::<Vec<_>>()
        .join(", ");
    let more = unreached.len().saturating_sub(LISTED_METHODS);
    vec![warning(
        "change_not_reached",
        format!(
            "{} of {} changed method(s) cannot be reached from main in the static call graph, so no scenario can \
             exercise them: {listed}{}. Calls through interfaces or overrides are not expanded, so this can \
             over-report.",
            unreached.len(),
            changed.len(),
            if more > 0 { format!(" and {more} more") } else { String::new() },
        ),
    )]
}

/// Method/constructor id -> its declaration without positions and derived data (CFG, analysis).
fn method_fingerprints(artifact: &Value) -> BTreeMap<String, Value> {
    let mut found = BTreeMap::new();
    collect_methods(artifact, &mut found);
    found
}

fn collect_methods(value: &Value, found: &mut BTreeMap<String, Value>) {
    match value {
        Value::Object(map) => {
            let is_method = matches!(
                map.get("kind").and_then(Value::as_str),
                Some("method" | "constructor")
            ) && map.contains_key("body");
            if let (true, Some(id)) = (is_method, map.get("id").and_then(Value::as_str)) {
                found.insert(id.to_owned(), strip_volatile(value));
            }
            for child in map.values() {
                collect_methods(child, found);
            }
        }
        Value::Array(items) => items.iter().for_each(|item| collect_methods(item, found)),
        _ => {}
    }
}

fn without_id(declaration: &Value) -> Value {
    let mut copy = declaration.clone();
    if let Some(map) = copy.as_object_mut() {
        map.remove("id");
    }
    copy
}

/// Positions, graphs and everything the parser resolves from bindings: type names there change when code moves to
/// another type or file (`Main.Item` -> `Item`) although the source does not.
fn is_derived(key: &str) -> bool {
    key.starts_with("resolved")
        || matches!(
            key,
            "source_range"
                | "cfg"
                | "analysis"
                | "calls"
                | "receiver_type"
                | "declaring_class"
                | "signature"
        )
}

fn strip_volatile(value: &Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.iter()
                .filter(|(key, _)| !is_derived(key))
                .map(|(key, child)| (key.clone(), strip_volatile(child)))
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.iter().map(strip_volatile).collect()),
        other => other.clone(),
    }
}

/// Method ids reachable from any `main(String[])` over `calls`/`constructs` edges of the project graph, or `None`
/// when the graph is missing or has no entry point.
fn reachable_methods(artifact: &Value) -> Option<BTreeSet<String>> {
    let graph = artifact.get("project_graph").filter(|g| g.is_object())?;
    let nodes = graph.get("nodes")?.as_array()?;
    let mut method_of: HashMap<&str, &str> = HashMap::new();
    for node in nodes {
        if let (Some(id), Some(method)) = (
            node.get("id").and_then(Value::as_str),
            node.get("method_id").and_then(Value::as_str),
        ) {
            method_of.insert(id, method);
        }
    }
    let mut calls: HashMap<&str, Vec<&str>> = HashMap::new();
    for edge in graph.get("edges")?.as_array()? {
        if matches!(
            edge.get("kind").and_then(Value::as_str),
            Some("calls" | "constructs")
        ) {
            if let (Some(from), Some(to)) = (
                edge.get("from").and_then(Value::as_str),
                edge.get("to").and_then(Value::as_str),
            ) {
                calls.entry(from).or_default().push(to);
            }
        }
    }
    let mut queue: VecDeque<&str> = method_of
        .iter()
        .filter(|(_, method)| method.ends_with(".main(String[])"))
        .map(|(id, _)| *id)
        .collect();
    if queue.is_empty() {
        return None;
    }
    let mut seen: BTreeSet<&str> = queue.iter().copied().collect();
    while let Some(current) = queue.pop_front() {
        for next in calls.get(current).into_iter().flatten() {
            if seen.insert(next) {
                queue.push_back(next);
            }
        }
    }
    Some(
        seen.iter()
            .filter_map(|id| method_of.get(id).map(|method| (*method).to_owned()))
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn case(stdin: &str) -> VerifyTestCase {
        VerifyTestCase {
            name: "c".to_owned(),
            stdin: stdin.to_owned(),
            expected_stdout: None,
            expected_stderr: None,
            expected_exit_code: None,
        }
    }

    fn method(id: &str, body: &str, line: u64) -> Value {
        json!({"kind": "method", "id": id, "body": [{"kind": "expression", "text": body,
            "source_range": {"line": line}}], "source_range": {"line": line}, "cfg": {"nodes": [line]}})
    }

    /// `A.main` calls `A.used`; `A.lonely` is not called by anything.
    fn artifact(used: &str, lonely: &str, shift: u64) -> Value {
        json!({
            "types": [{"members": [
                method("A.main(String[])", "run()", 1 + shift),
                method("A.used()", used, 5 + shift),
                method("A.lonely()", lonely, 9 + shift),
            ]}],
            "project_graph": {
                "nodes": [
                    {"id": "c0", "kind": "method", "method_id": "A.main(String[])"},
                    {"id": "c1", "kind": "method", "method_id": "A.used()"},
                    {"id": "c2", "kind": "method", "method_id": "A.lonely()"},
                    {"id": "t0", "kind": "class", "name": "A"},
                ],
                "edges": [
                    {"from": "t0", "to": "c0", "kind": "contains"},
                    {"from": "c0", "to": "c1", "kind": "calls"},
                ],
            },
        })
    }

    #[test]
    fn empty_stdin_everywhere_is_flagged_but_one_real_input_is_enough() {
        let flagged = scenario_warnings(&[case(""), case("  \n")]);
        assert_eq!(flagged[0].code, "empty_scenarios");
        assert!(scenario_warnings(&[case(""), case("add 1\n")]).is_empty());
    }

    #[test]
    fn a_missing_analysis_is_reported_instead_of_guessed() {
        let a = artifact("x", "y", 0);
        for pair in [(None, Some(&a)), (Some(&a), None), (None, None)] {
            assert_eq!(
                change_warnings(pair.0, pair.1)[0].code,
                "reachability_unknown"
            );
        }
    }

    #[test]
    fn identical_method_bodies_are_reported_even_if_lines_moved() {
        let before = artifact("x", "y", 0);
        let after = artifact("x", "y", 3);
        assert_eq!(
            change_warnings(Some(&before), Some(&after))[0].code,
            "no_method_changes"
        );
    }

    #[test]
    fn a_changed_method_that_main_reaches_raises_nothing() {
        let before = artifact("x", "y", 0);
        let after = artifact("x2", "y", 0);
        assert!(change_warnings(Some(&before), Some(&after)).is_empty());
    }

    #[test]
    fn resolved_type_names_do_not_make_moved_code_look_changed() {
        let mut before = artifact("x", "y", 0);
        let mut after = artifact("x", "y", 0);
        before["types"][0]["members"][1]["body"][0]["resolved_type"] = json!({"name": "Main.Item"});
        after["types"][0]["members"][1]["body"][0]["resolved_type"] = json!({"name": "Item"});
        assert_eq!(
            change_warnings(Some(&before), Some(&after))[0].code,
            "no_method_changes"
        );
    }

    #[test]
    fn code_that_only_moved_to_a_new_id_is_not_a_change() {
        let before = artifact("x", "y", 0);
        let mut after = artifact("x", "y", 0);
        after["types"][0]["members"][2]["id"] = json!("B.lonely()");
        after["project_graph"]["nodes"][2]["method_id"] = json!("B.lonely()");
        // nothing differs but an id, even though nothing calls the moved method
        assert_eq!(
            change_warnings(Some(&before), Some(&after))[0].code,
            "no_method_changes"
        );
    }

    #[test]
    fn new_code_under_a_new_id_counts_as_affected() {
        let before = artifact("x", "y", 0);
        let mut after = artifact("x", "y2", 0);
        after["types"][0]["members"][2]["id"] = json!("B.lonely()");
        after["project_graph"]["nodes"][2]["method_id"] = json!("B.lonely()");
        let warnings = change_warnings(Some(&before), Some(&after));
        assert_eq!(warnings[0].code, "change_not_reached", "{warnings:?}");
        assert!(warnings[0].message.contains("B.lonely()"), "{warnings:?}");
    }

    #[test]
    fn a_changed_method_that_nothing_calls_is_flagged_by_name() {
        let before = artifact("x", "y", 0);
        let after = artifact("x", "y2", 0);
        let warnings = change_warnings(Some(&before), Some(&after));
        assert_eq!(warnings.len(), 1);
        assert_eq!(warnings[0].code, "change_not_reached");
        assert!(warnings[0].message.contains("A.lonely()"), "{warnings:?}");
        assert!(warnings[0].message.starts_with("1 of 1"), "{warnings:?}");
    }

    #[test]
    fn without_a_main_method_or_graph_reachability_is_unknown() {
        let before = artifact("x", "y", 0);
        let mut after = artifact("x2", "y", 0);
        after["project_graph"] = Value::Null;
        assert_eq!(
            change_warnings(Some(&before), Some(&after))[0].code,
            "reachability_unknown"
        );
    }
}
