//! End-to-end tests that execute Java in the real isolated Docker runner.
//!
//! All tests are `#[ignore]`d so a plain `cargo test` stays Docker-free. Run them with
//! `cargo test --test docker_e2e -- --ignored` (needs a running Docker daemon and the runner image
//! `java-blueprints-runner:java21`; a missing prerequisite fails with a `DOCKER BLOCKER` message).

mod common;

use common::*;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

const LINK: &str = "shop.service.OrderLinker.link(List<Order>,List<Customer>,Map<Integer, String>)";
const DESCRIBE: &str = "shop.service.OrderLinker.describeAll(List<Order>,List<Customer>)";
const LINKER: &str = "src/main/java/shop/service/OrderLinker.java";

fn out(stream: &Value) -> String {
    stream["utf8"].as_str().unwrap_or_default().to_owned()
}

fn cases(inputs: &[(&str, &str)]) -> Value {
    Value::Array(
        inputs
            .iter()
            .map(|(name, stdin)| json!({"name": name, "stdin": stdin}))
            .collect(),
    )
}

/// Orders / customers / id modulus: duplicates (small modulus), misses, empty sides, failures.
const SCENARIOS: &[(&str, &str)] = &[
    ("empty-both", "0 0 5\n"),
    ("no-customers", "12 0 5\n"),
    ("no-orders", "0 12 5\n"),
    ("unique-ids", "40 40 1000\n"),
    ("heavy-duplicates", "60 45 3\n"),
    ("misses-and-nulls", "37 29 11\n"),
    ("larger", "300 250 40\n"),
    ("division-by-zero", "3 3 0\n"),
    ("missing-input", ""),
];

async fn optimized_lookup_project(api: &Api) -> Project {
    let project = api.create_from_fixture("lookup_opt").await;
    project.analyze("8").await;
    project.optimize("8", LINK).await;
    project.optimize("8", DESCRIBE).await;
    assert_eq!(project.revision().await, 3);
    project
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn optimized_project_behaves_identically_on_representative_inputs() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = optimized_lookup_project(&api).await;
    // the executed candidate really is the rewritten source
    let rewritten = project.source(LINKER, Some(3)).await;
    assert!(rewritten.contains("putIfAbsent") && rewritten.contains("computeIfAbsent"));
    assert!(project.source(LINKER, Some(1)).await.contains("break;"));

    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 3, "source_level": "8", "test_cases": cases(SCENARIOS)}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "passed", "{report}");
    assert_eq!(report["behaviorally_verified"], true);
    assert_eq!(report["executed_test_cases"], SCENARIOS.len());
    assert_eq!(report["baseline_compilation"]["main_class"], "shop.Main");
    assert_eq!(report["candidate_compilation"]["status"], "completed");

    let results = report["test_cases"].as_array().unwrap();
    let find = |name: &str| results.iter().find(|r| r["name"] == name).unwrap();
    for result in results {
        assert_eq!(result["passed"], true, "{result}");
        // stdout, stderr and exit code are all identical, byte for byte
        for field in [
            "stdout",
            "stderr",
            "exit_code",
            "timed_out",
            "output_overflow",
        ] {
            assert_eq!(
                result["baseline"][field], result["candidate"][field],
                "{} {field}",
                result["name"]
            );
        }
    }
    // deterministic expectations on the baseline itself
    assert_eq!(
        out(&find("empty-both")["baseline"]["stdout"]),
        "linked=0 owners=0 lines=0 hash=7\n"
    );
    assert_eq!(
        out(&find("no-customers")["baseline"]["stdout"]),
        "linked=0 owners=0 lines=0 hash=7\n"
    );
    assert_eq!(find("unique-ids")["baseline"]["exit_code"], 0);
    let duplicates = out(&find("heavy-duplicates")["baseline"]["stdout"]);
    let lines: usize = duplicates
        .split("lines=")
        .nth(1)
        .unwrap()
        .split(' ')
        .next()
        .unwrap()
        .parse()
        .unwrap();
    let linked: usize = duplicates
        .split("linked=")
        .nth(1)
        .unwrap()
        .split(' ')
        .next()
        .unwrap()
        .parse()
        .unwrap();
    assert!(
        lines > linked,
        "duplicate customer ids produce several matches per order: {duplicates}"
    );
    assert!(duplicates.contains("customer-"), "{duplicates}");
    // failures are reproduced exactly: same exit code, same exception, same trace
    let failing = find("division-by-zero");
    assert_eq!(failing["baseline"]["exit_code"], 1);
    assert!(out(&failing["baseline"]["stderr"]).contains("java.lang.ArithmeticException"));
    assert_eq!(
        failing["baseline"]["stderr"],
        failing["candidate"]["stderr"]
    );
    assert!(out(&find("missing-input")["baseline"]["stderr"]).contains("NoSuchElementException"));
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn verification_flags_behaviour_changes_and_surfaces_compile_errors() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    project.analyze("8").await; // lets the API find the single main class
    let linker = project.source(LINKER, None).await;
    let body = |candidate: u64, inputs: &[(&str, &str)]| json!({"baseline_revision": 1, "candidate_revision": candidate, "source_level": "8", "test_cases": cases(inputs)});

    // revision 2: a behaviour change (last duplicate wins instead of the first)
    let changed = linker.replace("owners.put(order.getId(), customer.getName());\n                    linked++;\n                    break;", "owners.put(order.getId(), customer.getName());\n                    linked++;");
    assert_ne!(changed, linker);
    project
        .put_file(LINKER, &changed, 1)
        .await
        .expect_status(200);
    let report = project
        .verify(&body(2, &[("same", "5 5 1000\n"), ("dups", "20 20 3\n")]))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "failed", "{report}");
    assert_eq!(report["behaviorally_verified"], false);
    let by_name = |n: &str| {
        report["test_cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|t| t["name"] == n)
            .unwrap()
            .clone()
    };
    assert_eq!(
        by_name("same")["passed"],
        true,
        "inputs without duplicates do not expose the change"
    );
    let dups = by_name("dups");
    assert_eq!(dups["passed"], false);
    assert!(
        dups["mismatches"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["field"] == "stdout"),
        "{dups}"
    );

    // revision 3: does not compile -> reported with javac's message, nothing executed
    project
        .put_file(
            LINKER,
            &linker.replace("return linked;", "return linked"),
            2,
        )
        .await
        .expect_status(200);
    let broken = project
        .verify(&body(3, &[("x", "1 1 5\n")]))
        .await
        .expect_status(200)
        .json();
    assert_eq!(broken["status"], "compilation_failed", "{broken}");
    assert_eq!(broken["executed_test_cases"], 0);
    assert_eq!(broken["baseline_compilation"]["status"], "completed");
    assert_eq!(broken["candidate_compilation"]["status"], "failed");
    assert!(
        broken["candidate_compilation"]["diagnostics"]
            .to_string()
            .contains("OrderLinker.java"),
        "{broken}"
    );
    assert_eq!(broken["behaviorally_verified"], false);

    // request errors
    project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "test_cases": []}))
        .await
        .expect_error(400, "invalid_request");
    project
        .verify(&body(9, &[("x", "1 1 5\n")]))
        .await
        .expect_error(404, "revision_not_found");
    project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "main_class": "shop.Missing", "source_level": "8", "test_cases": cases(&[("x", "")])}))
        .await
        .expect_status(200);
    let wrong = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "main_class": "shop.Missing", "source_level": "8", "test_cases": cases(&[("x", "")])}))
        .await
        .json();
    assert_eq!(wrong["baseline_compilation"]["status"], "missing_main");
    project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "main_class": "not a class", "test_cases": cases(&[("x", "")])}))
        .await
        .expect_error(400, "invalid_request");
    project.verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "source_level": "1.4", "test_cases": cases(&[("x", "")])})).await.expect_error(400, "unsupported_source_level");
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn java_source_levels_are_enforced_by_the_real_compiler() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;

    let legacy = api.create_from_fixture("legacy_java8").await;
    legacy.analyze("8").await;
    for level in ["8", "17", "21"] {
        let report = legacy
            .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "source_level": level, "test_cases": cases(&[("run", "")])}))
            .await
            .expect_status(200)
            .json();
        assert_eq!(report["status"], "passed", "level {level}: {report}");
        assert_eq!(
            out(&report["test_cases"][0]["baseline"]["stdout"]),
            "found=true\ncontains=true\nfib=610\n",
            "level {level}"
        );
        assert_eq!(
            report["baseline_compilation"]["main_class"],
            "com.legacy.app.Main"
        );
    }

    // a record is Java 16+: javac --release 8 must reject it, 17 must run it
    let record = "public class Rec {\n    record P(int x, int y) { int sum() { return x + y; } }\n    public static void main(String[] a) { System.out.println(new P(2, 3).sum()); }\n}\n";
    let project = api
        .create_from_entries(
            "rec.zip",
            &[("src/Rec.java".to_owned(), record.as_bytes().to_vec())],
        )
        .await;
    let run = |level: &'static str| {
        let project = project.clone();
        async move {
            project
                .verify(&json!({"baseline_revision": 1, "candidate_revision": 1, "main_class": "Rec", "source_level": level, "test_cases": cases(&[("run", "")])}))
                .await
                .expect_status(200)
                .json()
        }
    };
    let at8 = run("8").await;
    assert_eq!(at8["status"], "compilation_failed", "{at8}");
    assert_eq!(at8["baseline_compilation"]["status"], "failed");
    let at17 = run("17").await;
    assert_eq!(at17["status"], "passed", "{at17}");
    assert_eq!(out(&at17["test_cases"][0]["baseline"]["stdout"]), "5\n");
}

fn median(values: &mut Vec<u64>) -> u64 {
    values.sort_unstable();
    let n = values.len();
    if n % 2 == 1 {
        values[n / 2]
    } else {
        ((values[n / 2 - 1] + values[n / 2]) as f64 / 2.0).round() as u64
    }
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn benchmark_report_is_built_from_real_measurements() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = optimized_lookup_project(&api).await;

    let sizes = [
        (200, "200 200 90\n"),
        (800, "800 800 400\n"),
        (1600, "1600 1600 900\n"),
    ];
    let workloads: Vec<Value> = sizes
        .iter()
        .map(|(n, stdin)| json!({"name": format!("n={n}"), "size": n, "stdin": stdin}))
        .collect();
    let report = project
        .benchmark(&json!({
            "baseline_revision": 1, "candidate_revision": 3, "source_level": "8",
            "workloads": workloads, "warmup_iterations": 3, "measured_iterations": 12,
        }))
        .await
        .expect_status(200)
        .json();

    assert_eq!(report["status"], "completed", "{report}");
    assert_eq!(report["settings"]["warmup_iterations"], 3);
    assert_eq!(report["settings"]["measured_iterations"], 12);
    assert_eq!(report["settings"]["class_loading"], "fresh");
    assert_eq!(report["settings"]["main_class"], "shop.Main");

    // environment metadata comes from inside the container
    let env = &report["environment"];
    assert_eq!(env["network"], "none");
    assert_eq!(env["cpu_limit"], "1");
    assert_eq!(env["memory_limit"], "512m");
    assert_eq!(env["runner_image"], runner_image().as_str());
    for side in ["baseline_jvm", "candidate_jvm"] {
        assert!(
            env[side]["java_version"]
                .as_str()
                .unwrap()
                .starts_with("21"),
            "{env}"
        );
        assert!(env[side]["available_processors"].as_u64().unwrap() >= 1);
        assert!(env[side]["max_heap_bytes"].as_u64().unwrap() > 0);
        assert!(env[side]["jvm_flags"]
            .to_string()
            .contains("-Djava.security.manager=allow"));
    }
    // compile/setup/startup time is reported apart from algorithm time
    for side in ["baseline", "candidate"] {
        let t = &report["timings"][side];
        assert!(
            t["compile_and_setup_ms"].as_u64().unwrap() > 0
                && t["harness_setup_ms"].as_u64().unwrap() > 0
        );
        assert!(t["benchmark_process_ms"].as_u64().unwrap() > 0);
    }

    // 1. static complexity, 2. functional outcome, 3. runtime: three separate sections
    let change = report["static_complexity"]["changes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["method_id"] == LINK)
        .unwrap();
    assert_eq!(
        (change["before"].as_str(), change["after"].as_str()),
        (Some("O(n*m)"), Some("O(n + m)"))
    );
    assert_eq!(report["functional"]["equivalent_on_all_workloads"], true);
    assert_eq!(report["functional"]["non_equivalent_workloads"], json!([]));

    let runtime = report["runtime"]["workloads"].as_array().unwrap();
    assert_eq!(runtime.len(), sizes.len());
    for (workload, (n, stdin)) in runtime.iter().zip(sizes) {
        assert_eq!(workload["declared_size"], n);
        assert_eq!(
            workload["stdin_sha256"],
            format!("{:x}", Sha256::digest(stdin.as_bytes())).as_str()
        );
        assert_eq!(workload["equivalent"], true);
        let mut medians = Vec::new();
        for side in ["baseline", "candidate"] {
            let mut samples: Vec<u64> = workload[side]["samples_ns"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_u64().unwrap())
                .collect();
            assert_eq!(samples.len(), 12, "measured iterations are all recorded");
            assert_eq!(
                workload[side]["warmup_ns"].as_array().unwrap().len(),
                3,
                "warmup iterations are recorded but excluded"
            );
            assert!(
                samples.iter().all(|&s| s > 0),
                "monotonic nanosecond durations"
            );
            assert_eq!(workload[side]["outcome"], "returned");
            assert_eq!(workload[side]["stable_across_iterations"], true);
            // the reported statistics are recomputed from the raw samples, not carried over from anywhere
            let stats = &workload[format!("{side}_stats")];
            assert_eq!(stats["iterations"], 12);
            assert_eq!(stats["min_ns"], *samples.iter().min().unwrap());
            assert_eq!(stats["max_ns"], *samples.iter().max().unwrap());
            let m = median(&mut samples);
            assert_eq!(stats["median_ns"], m);
            assert!(
                stats["min_ns"].as_u64().unwrap() <= m && m <= stats["max_ns"].as_u64().unwrap()
            );
            medians.push(m);
        }
        assert_eq!(
            workload["baseline"]["stdout_sha256"],
            workload["candidate"]["stdout_sha256"]
        );
        // a speedup exists only with a supporting verdict, equals the ratio of the measured medians, and
        // comes with evidence; no particular outcome is required because timings are noisy
        let verdict = workload["verdict"].as_str().unwrap();
        match verdict {
            "faster" | "slower" => {
                let ratio = medians[0] as f64 / medians[1] as f64;
                let speedup = workload["speedup"].as_f64().expect("claimed speedup");
                assert!(
                    (speedup - ratio).abs() < 1e-9 * ratio.max(1.0),
                    "{speedup} vs {ratio}"
                );
                assert!(workload["p_value"].as_f64().unwrap() < 0.01);
                let ci = workload["speedup_ci95"].as_array().unwrap();
                assert!(
                    ci[0].as_f64().unwrap() <= speedup
                        && speedup <= ci[1].as_f64().unwrap() * 1.0000001
                        || verdict == "slower",
                    "{ci:?} {speedup}"
                );
                if verdict == "faster" {
                    assert!(ci[0].as_f64().unwrap() > 1.0 && speedup >= 1.1);
                } else {
                    assert!(ci[1].as_f64().unwrap() < 1.0 && speedup <= 1.0 / 1.1);
                }
            }
            "no_significant_difference" | "insufficient_evidence" => {
                assert_eq!(workload["speedup"], Value::Null, "{workload}")
            }
            other => panic!("unexpected verdict {other}: {workload}"),
        }
    }
    assert!(report["limitations"].as_array().unwrap().len() >= 5);
    // the stored report is retrievable and identical
    let fetched = project
        .api
        .get(&project.path(&format!(
            "/benchmark/{}",
            report["report_id"].as_str().unwrap()
        )))
        .await
        .expect_status(200)
        .json();
    assert_eq!(fetched["runtime"], report["runtime"]);
    project
        .api
        .get(&project.path("/benchmark/00000000-0000-0000-0000-000000000000"))
        .await
        .expect_error(404, "benchmark_report_not_found");
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn benchmark_makes_no_claim_without_evidence_and_flags_behaviour_changes() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = optimized_lookup_project(&api).await;
    let workload = json!([{"name": "n=1500", "size": 1500, "stdin": "1500 1500 900\n"}]);

    // few iterations: never a speedup, whatever the timings are
    let few = project
        .benchmark(&json!({"baseline_revision": 1, "candidate_revision": 3, "source_level": "8", "workloads": workload, "warmup_iterations": 1, "measured_iterations": 3}))
        .await
        .expect_status(200)
        .json();
    let w = &few["runtime"]["workloads"][0];
    assert_eq!(w["verdict"], "insufficient_evidence");
    assert_eq!(w["speedup"], Value::Null);
    assert_eq!(w["speedup_ci95"], Value::Null);
    assert!(w["notes"].to_string().contains("fewer than 10"));
    assert_eq!(
        few["runtime"]["overall"]["verdict"],
        "insufficient_evidence"
    );

    // A/A: identical revisions cannot be faster than themselves
    let same = project
        .benchmark(&json!({"baseline_revision": 1, "candidate_revision": 1, "source_level": "8", "workloads": workload, "warmup_iterations": 2, "measured_iterations": 12}))
        .await
        .expect_status(200)
        .json();
    let verdict = same["runtime"]["workloads"][0]["verdict"].as_str().unwrap();
    assert_ne!(verdict, "faster", "{same}");
    assert_ne!(verdict, "slower", "{same}");
    assert_eq!(same["runtime"]["workloads"][0]["speedup"], Value::Null);
    assert!(same["static_complexity"]["changes"]
        .as_array()
        .unwrap()
        .is_empty());

    // a "fast" candidate that changes behaviour is not an optimization
    let source = project.source(LINKER, Some(1)).await;
    let wrong = source.replace(
        "linked++;\n                    break;",
        "linked += 2;\n                    break;",
    );
    assert_ne!(wrong, source);
    project.put_file(LINKER, &wrong, 3).await.expect_status(200);
    let changed = project
        .benchmark(&json!({"baseline_revision": 1, "candidate_revision": 4, "source_level": "8", "workloads": workload, "warmup_iterations": 1, "measured_iterations": 10}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(changed["runtime"]["overall"]["verdict"], "not_equivalent");
    assert_eq!(changed["functional"]["equivalent_on_all_workloads"], false);
    assert_eq!(
        changed["functional"]["non_equivalent_workloads"],
        json!(["n=1500"])
    );
    let w = &changed["runtime"]["workloads"][0];
    assert_eq!(w["equivalent"], false);
    assert_eq!(w["speedup"], Value::Null);
    assert_eq!(
        w["p_value"],
        Value::Null,
        "timings of non-equivalent programs are not compared"
    );
    assert!(changed["runtime"]["overall"]["summary"]
        .as_str()
        .unwrap()
        .contains("not an optimization"));
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn benchmark_surfaces_compile_failures_timeouts_and_bad_requests() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = api.create_from_fixture("lookup_opt").await;
    project.analyze("8").await;
    let linker = project.source(LINKER, None).await;
    let workload = json!([{"name": "w", "stdin": "10 10 5\n"}]);
    let request = |candidate: u64, extra: Value| {
        let mut body = json!({"baseline_revision": 1, "candidate_revision": candidate, "source_level": "8", "workloads": workload.clone(), "warmup_iterations": 0, "measured_iterations": 3});
        for (k, v) in extra.as_object().cloned().unwrap_or_default() {
            body[k] = v;
        }
        body
    };

    // compile error in the candidate
    project
        .put_file(
            LINKER,
            &linker.replace("return linked;", "return linked"),
            1,
        )
        .await
        .expect_status(200);
    let broken = project
        .benchmark(&request(2, json!({})))
        .await
        .expect_status(200)
        .json();
    assert_eq!(broken["status"], "compilation_failed", "{broken}");
    assert_eq!(broken["baseline_compilation"]["status"], "completed");
    assert_eq!(broken["candidate_compilation"]["status"], "failed");
    assert_eq!(broken["runtime"]["overall"]["verdict"], "incomplete");
    assert_eq!(broken["runtime"]["workloads"][0]["speedup"], Value::Null);

    // a candidate that never finishes is cut off by the per-iteration limit
    let looping = linker.replace(
        "int linked = 0;",
        "int linked = 0;\n        while (linked >= 0) { linked = linked + 0; }",
    );
    project
        .put_file(LINKER, &looping, 2)
        .await
        .expect_status(200);
    let started = std::time::Instant::now();
    let cut = project
        .benchmark(&request(3, json!({"iteration_timeout_ms": 1500})))
        .await
        .expect_status(200)
        .json();
    assert!(
        started.elapsed() < std::time::Duration::from_secs(90),
        "the benchmark must not hang"
    );
    assert_eq!(cut["status"], "incomplete", "{cut}");
    assert_eq!(cut["runtime"]["workloads"][0]["verdict"], "incomplete");
    assert_eq!(
        cut["runtime"]["workloads"][0]["candidate"]["status"],
        "timed_out"
    );
    assert!(
        cut["timings"]["candidate"]["benchmark_process_ms"]
            .as_u64()
            .unwrap()
            < 30_000
    );

    // validation
    for (extra, field) in [
        (json!({"workloads": []}), "workloads"),
        (json!({"measured_iterations": 0}), "measured"),
        (json!({"measured_iterations": 101}), "measured"),
        (json!({"warmup_iterations": 51}), "warmup"),
        (json!({"iteration_timeout_ms": 0}), "timeout"),
        (
            json!({"workloads": [{"name": "a", "stdin": ""}, {"name": "a", "stdin": ""}]}),
            "unique",
        ),
    ] {
        let error = project
            .benchmark(&request(1, extra))
            .await
            .expect_error(400, "invalid_request");
        let _ = (field, error);
    }
    project
        .benchmark(&request(1, json!({"source_level": "1.4"})))
        .await
        .expect_error(400, "unsupported_source_level");
    project
        .benchmark(&request(9, json!({})))
        .await
        .expect_error(404, "revision_not_found");
    project
        .api
        .post_json(
            &project.path("/benchmark"),
            &json!({"baseline_revision": 1}),
        )
        .await
        .expect_error(400, "invalid_request");
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn organizer_workflow_runs_in_the_real_runner() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = api
        .create_project("Organizer.java", organizer_source())
        .await;
    project.analyze_single_file().await;
    let method = "Organizer.linkTasksToContacts(List<Task>,List<Contact>,Map<Integer, String>)";
    project.optimize("21", method).await;

    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": 2, "test_cases": cases(&[("quit", "0\n"), ("eof", "")])}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "passed", "{report}");
    assert_eq!(report["behaviorally_verified"], true);
    // green, but the optimized method is not reachable from Organizer.main: the report must say so
    let warnings = report["warnings"].as_array().unwrap();
    assert!(
        warnings.iter().any(|w| w["code"] == "change_not_reached"
            && w["message"]
                .as_str()
                .unwrap()
                .contains("linkTasksToContacts")),
        "{report}"
    );
    assert_eq!(report["baseline_compilation"]["main_class"], "Organizer");
    for result in report["test_cases"].as_array().unwrap() {
        assert_eq!(result["baseline"]["stdout"], result["candidate"]["stdout"]);
        assert_eq!(
            result["baseline"]["exit_code"],
            result["candidate"]["exit_code"]
        );
    }
}

// ------------------------------------------------------------------ demo monolith (stdin-driven)

/// Scenarios that reach the optimized method (`merge_inventory`) and ones that do not.
fn demo_cases() -> Value {
    cases(&[
        ("help", "help\nexit\n"),
        ("inventory", "inventory\nexit\n"),
        ("merge-seed-duplicates", "merge_inventory\ninventory\nexit\n"),
        (
            "merge-after-add",
            "add_item ITM-101;Ноутбук Pro 15;Электроника;90000;2\nadd_item ITM-105;Кабель;Аксессуары;300;40\nmerge_inventory\ninventory\nexit\n",
        ),
        ("unknown-command", "foo\nexit\n"),
        ("eof-without-exit", "orders\n"),
    ])
}

async fn demo_project(api: &Api) -> Project {
    api.create_project("Main.java", demo_source().into_bytes())
        .await
}

async fn put_candidate(project: &Project, source: &str) -> u64 {
    let revision = project.revision().await;
    project
        .api
        .put_json(
            &project.path("/source"),
            &json!({"source": source, "expected_revision": revision}),
        )
        .await
        .expect_status(200)
        .json()["revision"]
        .as_u64()
        .unwrap()
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn demo_monolith_manual_optimization_is_verified_on_scenarios_that_reach_it() {
    // The automatic optimizer currently refuses this method (equals() receiver is the outer value and the loop
    // constructs a user class), so the candidate is the monolith's own `mergeDuplicateItemsOptimized`.
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = demo_project(&api).await;
    project.analyze_single_file().await;
    let original = demo_source();
    let call = "DataOptimizer.mergeDuplicateItemsQuadratic(context.inventory)";
    assert!(original.contains(call));
    let candidate = put_candidate(
        &project,
        &original.replace(
            call,
            "DataOptimizer.mergeDuplicateItemsOptimized(context.inventory)",
        ),
    )
    .await;

    project.analyze_single_file().await;
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": candidate, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "passed", "{report}");
    assert_eq!(report["behaviorally_verified"], true);
    // main (which now calls the other merge) changed and is reachable, and every scenario has input
    assert_eq!(report["warnings"], json!([]), "{report}");
    // the gate is meaningful: the merge scenario really executed the defective method (duplicates collapsed)
    let merge = report["test_cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["name"] == "merge-seed-duplicates")
        .unwrap();
    assert!(
        out(&merge["baseline"]["stdout"]).contains("Уникальных позиций: 4"),
        "{merge}"
    );
    assert_eq!(merge["baseline"]["stdout"], merge["candidate"]["stdout"]);
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn demo_monolith_automatic_optimization_is_verified_on_scenarios_that_reach_it() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = demo_project(&api).await;
    project.analyze_single_file().await;
    let method = "Main.DataOptimizer.mergeDuplicateItemsQuadratic(List<Item>)";
    let before = project.complexity_of(method).await;
    assert_eq!(before["time_complexity"], "O(n^2)", "{before}");
    let applied = project.optimize("21", method).await;
    let revision = applied["revision"].as_u64().unwrap();

    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": revision, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "passed", "{report}");
    let merge = report["test_cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["name"] == "merge-after-add")
        .unwrap();
    assert!(
        out(&merge["baseline"]["stdout"]).contains("Уникальных позиций: 5"),
        "{merge}"
    );
    assert_eq!(merge["baseline"]["stdout"], merge["candidate"]["stdout"]);
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn demo_monolith_mutants_are_caught_only_by_scenarios_that_reach_them() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = demo_project(&api).await;
    project.analyze_single_file().await;
    let original = demo_source();

    // mutant 1: changes the merge command's output
    let merge_line = "Слияние завершено. Уникальных позиций: %d%n";
    assert!(original.contains(merge_line));
    let merge_mutant = put_candidate(
        &project,
        &original.replace(merge_line, "Слияние выполнено: %d%n"),
    )
    .await;
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": merge_mutant, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "failed", "{report}");
    let failed: Vec<&str> = report["test_cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|c| c["passed"] == false)
        .map(|c| c["name"].as_str().unwrap())
        .collect();
    assert_eq!(
        failed,
        ["merge-seed-duplicates", "merge-after-add"],
        "{report}"
    );

    // mutant 2: changes only the help text, so scenarios that never print it stay green
    let help_line = "Показать эту справку";
    assert!(original.contains(help_line));
    let help_mutant =
        put_candidate(&project, &original.replace(help_line, "Показать справку")).await;
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": help_mutant, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    let failed: Vec<&str> = report["test_cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|c| c["passed"] == false)
        .map(|c| c["name"].as_str().unwrap())
        .collect();
    assert_eq!(failed, ["help"], "{report}");
}

#[tokio::test]
#[ignore = "needs Docker and the runner image"]
async fn verify_warns_when_the_result_proves_little() {
    let server = TestServer::start_docker().await;
    let api = server.client().await;
    let project = demo_project(&api).await;
    project.analyze_single_file().await;
    let original = demo_source();
    let codes = |report: &Value| -> Vec<String> {
        report["warnings"]
            .as_array()
            .unwrap()
            .iter()
            .map(|w| w["code"].as_str().unwrap().to_owned())
            .collect()
    };

    // 1. the candidate only changes a method that nothing calls: green, but nothing exercised it
    let unused = "public static List<Item> mergeDuplicateItemsOptimized(List<Item> items) {";
    assert!(original.contains(unused));
    let edited = original.replace(
        unused,
        &format!("{unused}\n            System.out.println(\"never printed\");"),
    );
    let candidate = put_candidate(&project, &edited).await;
    project.analyze_single_file().await;
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": candidate, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(report["status"], "passed", "{report}");
    assert_eq!(report["behaviorally_verified"], true);
    assert_eq!(codes(&report), ["change_not_reached"], "{report}");
    let message = report["warnings"][0]["message"].as_str().unwrap();
    assert!(
        message.contains("mergeDuplicateItemsOptimized"),
        "{message}"
    );

    // 2. a single scenario without any input
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": candidate, "test_cases": cases(&[("empty", "")])}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(
        codes(&report),
        ["empty_scenarios", "change_not_reached"],
        "{report}"
    );

    // 3. the same revision against itself: nothing was changed at all
    let report = project
        .verify(
            &json!({"baseline_revision": 1, "candidate_revision": 1, "test_cases": demo_cases()}),
        )
        .await
        .expect_status(200)
        .json();
    assert_eq!(codes(&report), ["no_method_changes"], "{report}");

    // 4. an unanalyzed candidate cannot be checked, and the report says so instead of staying silent
    let unanalyzed = put_candidate(&project, &format!("{edited}\n// touched\n")).await;
    let report = project
        .verify(&json!({"baseline_revision": 1, "candidate_revision": unanalyzed, "test_cases": demo_cases()}))
        .await
        .expect_status(200)
        .json();
    assert_eq!(codes(&report), ["reachability_unknown"], "{report}");

    // the persisted report keeps the warnings
    let id = report["report_id"].as_str().unwrap();
    let stored = project
        .api
        .get(&project.path(&format!("/verify/{id}")))
        .await
        .expect_status(200)
        .json();
    assert_eq!(stored["warnings"], report["warnings"]);
}
