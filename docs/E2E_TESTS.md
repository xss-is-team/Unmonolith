# End-to-end test suite

Real tests, no mocks: each test starts the real Axum server on an OS-assigned port with a private temporary
data directory, talks to it through `reqwest`, and uses the real parser JAR built from `apps/parser`.
Docker-backed tests execute Java in the real isolated runner image. Nothing is faked: parser output, HTTP
responses, verification and benchmark numbers all come from the running system.

## Layout

| Path | Content |
|---|---|
| `apps/api/tests/common/mod.rs` | harness: `TestServer` (per-test server, shutdown on drop, failure transcript), typed `Api`/`Project` client, fixture and ZIP helpers, JAR freshness check, `require_docker()` |
| `apps/api/tests/fixtures/` | readable Java projects: `legacy_java8` (4 packages), `unresolved_dep`, `lookup_opt` (safe nested lookups, duplicates, null names), `unsafe_lookups` (5 unsafe scans), `cfg_edit`; `Organizer.java` is read from the parser acceptance resources |
| `tests/e2e_import.rs` | ZIP import, file tree/content, hostile archives, limits, session isolation, single-file compatibility |
| `tests/e2e_analysis.rs` | multi-file analysis, graph, CFG and ranges, Big O, unresolved dependencies, source levels, Organizer workflow |
| `tests/e2e_edit.rs` | optimization discovery/preview/apply/rollback, refusals, CFG `move_statement`, optimistic locking, concurrency, ownership |
| `tests/docker_e2e.rs` | **`#[ignore]`d, needs Docker**: executed equivalence, verification, source levels in `javac`, benchmark evidence, timeouts |
| `apps/parser/src/test/java/.../LegacyJavaCompatibilityTest.java` | JUnit: per-level language features, rejected levels |

## Prerequisites and commands (from the repository root)

```sh
./dev parser-build        # builds apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar (runs the Maven tests)
./dev runner-build        # Docker tests only: builds java-blueprints-runner:java21

( cd apps/parser && mvn test )                          # JUnit, visible summary
./dev api-test                                          # Rust unit tests + fast E2E (Docker tests stay ignored)
./dev api-e2e                                           # only the fast HTTP E2E files
./dev api-e2e-docker                                    # Docker-backed E2E (cargo test --test docker_e2e -- --ignored)
( cd apps/api && cargo test -- --ignored )              # also the opt-in real_* tests in src/api.rs
```

Environment overrides: `BLUEPRINTS_REAL_JAR`, `BLUEPRINTS_REAL_JAVA`, `BLUEPRINTS_REAL_RUNNER_IMAGE`.

The harness refuses to run against a parser JAR older than `apps/parser/src` (message: rebuild with
`./dev parser-build`), so the tests always exercise the current parser. A missing JAR fails with a message that
says how to build it. Docker tests fail with a `DOCKER BLOCKER:` message when the daemon or image is missing;
without `--ignored` they are reported as *ignored*, i.e. not run.

## Isolation and cleanup

* One server, one temp directory and one session per test; tests share nothing and run in parallel by default.
* Server shutdown, task abort and temp-dir removal happen in `Drop`, so they also run after a panic. Runner
  containers are removed by the API's own guard when the request future is dropped.
* On failure the last HTTP exchanges are printed (request, status, body) with the data directory path.
* Readiness is one bounded request to `/healthz`; there are no fixed sleeps.
* The earlier "text file busy" flake (stub executables written and exec'ed while another test forks) is fixed in
  `src/lib.rs::test_util::install_executable`, which writes the stub with its mode set and returns only once
  `exec` no longer fails with ETXTBSY.

## Benchmark assertions

Timings are noisy, so tests never require a speedup. They check structure and honesty instead: sample and warmup
counts, nanosecond units, JVM metadata from inside the container, separate compile/setup timings, stdin hashes,
statistics recomputed from the raw samples, and that `speedup` is non-null only for a `faster`/`slower` verdict that
carries a matching ratio, p-value and confidence interval. Fewer than 10 iterations, A/A runs and non-equivalent
candidates must not produce a claim.
