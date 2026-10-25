# Case compliance — Java Blueprints

Date: 2026-10-25 · Branch `wip` on top of `main` @ `53f5484`. Updated after the verify-warnings, demo-monolith and complexity/optimizer work (uncommitted at the time of writing).
Method: requirement-by-requirement audit of the case PDF (12 slides, read in full including slide 4 mockup
graphics) against source code and test runs. Backend and frontend are rated separately.
Status: ✅ done · 🟡 partial · ❌ missing · ❓ not verified. Evidence kinds are kept apart:
**code** (file reference), **test** (name), **run** (executed in this audit), **claimed** (docs only).

## Runs behind this document

| Run | Result |
|---|---|
| `cargo test` (apps/api), fresh JAR built by `mvn package` | lib 73 passed (7 ignored); `e2e_analysis` 6/6, `e2e_edit` 13/13, `e2e_import` 6/6. The e2e files refuse to run when the JAR is older than `apps/parser/src` (`apps/api/tests/common/mod.rs:49`): rebuild with `./dev parser-build` after any pull. |
| `cargo test --test docker_e2e -- --ignored` (real Docker, runner image) | 11/11 passed. Run with `BLUEPRINTS_REAL_RUNNER_IMAGE=<image id>`: resolving the image by tag failed inside the test process on the audit machine although the same `docker image inspect` worked in a shell. |
| `cargo test --lib -- --ignored` (7 `real_*` tests, real JAR + JDK + Docker) | 7/7 passed |
| Parser `mvn test` (Maven 3.9.9, JDK 26 with `release 21`) | 173 tests, 0 failures (Acceptance 13, Analysis 26, Cfg 24, ComplexityBenchmark 11, LegacyJava 28, Main 7, Optimizer 14, ProjectAnalyzer 8, ProjectGraph 2, Semantic 12, StatementMove 9, Transform 19) |
| Web `tsc` / `eslint` / `next build` | **not run** (no `pnpm`, no `node_modules`). Every frontend status below is from reading code. |
| Parser CLI on `acceptance/Organizer.java` and `acceptance/Main.java` | run directly (see R13/R16) |

## Requirement matrix

Mandatory = case slides 2, 3, 5, 6 (gate), 7, 10, 11. Optional = slide 6 decomposition, slide 8 bonus.

| ID | Slide | Requirement | Backend | Frontend | Evidence | Gap |
|---|---|---|---|---|---|---|
| R1 | 2 | Web tool for a ≈1000-line monolith | ✅ | 🟡 ❓ | test `e2e_analysis::organizer_monolith_workflow_still_works_end_to_end` (976 lines) | UI not run |
| R2 | 2 | Program understood as code and as graph | ✅ | ✅ | `CfgBuilder.java`, `ProjectGraph.java`, `CfgTest`, `ProjectGraphTest`; UI mounts both viewers (`project-workspace.tsx:500,513`) | — |
| R3 | 2 | Helps improve the program | ✅ | 🟡 | `Optimizer.java`, `GET /optimizations`; run: 1 proposal on Organizer, 2 on `acceptance/Main.java` | UI shows only text recommendations |
| R4 | 2 | **Key contract**: tests confirm behavior preserved | ✅ engine · 🟡 UI | 🟡 | `runner.rs` `compare_observations` (stdout, stderr, exit code, timeout, overflow), `docker_e2e` 11/11; mutants are caught only by scenarios that reach them (`demo_monolith_mutants_are_caught_only_by_scenarios_that_reach_them`); weak runs are flagged by `warnings[]` (`verify_warns_when_the_result_proves_little`, `verify_hints.rs`) | UI does not show `warnings[]` yet; scenarios are still user-supplied |
| R5 | 3 | 01 Load `.java` | ✅ | ✅ | `single_file_uploads_keep_working_unchanged`, ZIP `e2e_import` 6/6; `upload-panel.tsx` | UI accepts `.java` only |
| R6 | 3 | 02 Parse | ✅ | ✅ | JDT with bindings; `SemanticTest`, `MainTest` (surefire) | — |
| R7 | 3 | 03 Build graph | ✅ | ✅ | CFG + project graph | methods without body have no CFG |
| R8 | 3 | 04 Explore and edit, «Java ↔ блок-схема» two-way | 🟡 | 🟡 | `replace_condition`, `move_statement`, optimizations, rollback: `e2e_edit` 13/13, `TransformTest`, `StatementMoveTest` | no block add/remove/drag, no method extraction; UI exposes only `replace_condition` + text editing |
| R9 | 3 | 05 Assemble Java | ✅ | ✅ | ASTRewrite → validated new revision; `real_ast_rewrite_http_end_to_end` | — |
| R10 | 3 | 06 Run and compare | ✅ | ✅ | `real_container_http_verification`, `docker_e2e` | — |
| R11 | 3 | Mandatory finish: full path | 🟡 | 🟡 | `real_optimize_verify_and_benchmark_end_to_end`, `organizer_workflow_runs_in_the_real_runner` | path runs, but verify is empty on Organizer; UI lacks the optimize step |
| R12 | 4 | UI concept (mockup) | 🟡 | 🟡 | see «Mockup comparison» | no block palette, Debug, metrics tab, optimize/export buttons |
| R13 | 5 | Complexity of method/fragment | ✅ | ✅ | `ComplexityAnalyzer.java`; measured before the latest changes: Organizer 30/103 methods `unknown`, Main 12/67 (not re-measured) | frequent `unknown` |
| R14 | 5 | Defect + risk + recommendation | ✅ | ✅ | findings `repeated_collection_scan`, `nested_collection_iteration`; `cfg-viewer.tsx:416-423` | risk is inside `explanation`; two finding kinds |
| R15 | 5 | Code line and graph node | ✅ | ✅ | `source_range`, `node_ids`; `cfg-viewer.tsx:220,436-444` | — |
| R16 | 5 | Fix with expected effect | ✅ | ❌ | `/optimizations` (`method_before/after`, `scan_before/after`), `POST /benchmark`; the slide-5 method `mergeDuplicateItemsQuadratic` is `O(n^2)`, applicable, optimized and verified in Docker (`demo_monolith_automatic_optimization_is_verified_on_scenarios_that_reach_it`) | UI shows no effect; `reconcileOrdersWithTransactions` stays refused (mutates existing elements) |
| R17 | 5 | Diagnosis verifiable | ✅ | 🟡 | `confidence`, `assumptions`, `limitations`, honest `unknown`; benchmark verdicts; `String +=` in a loop is `medium` with finding `string_concat_in_loop`, Euclid is `O(log n)` (`AnalysisTest`, `AcceptanceTest.classifiesComplexityOfTheFixtureMethods`) | recursion, streams and unmodelled library calls stay `unknown` |
| R18 | 6 | Auto decomposition (optional) | ❌ | ❌ | — | not started |
| R19 | 6 | Quality gate: build + tests = preserved | 🟡 | 🟡 | `/verify` compiles and runs both revisions | see R4 |
| R20 | 7 | Mandatory Java scope 1–4 | 🟡 | n/a | `AcceptanceTest::coversMandatoryLanguageConstructs`, `LegacyJavaCompatibilityTest` | no per-item checklist test |
| R21 | 7 | Case technical document | ❌ | n/a | — | not in repo; organizers' artifact |
| R22 | 8 | Bonus: record, **enum**, generics, lambda, `java.util.function`, Stream | 🟡 | n/a | parsed/compiled in acceptance fixtures; CFG loss for enum-constant bodies / anonymous classes claimed in `docs/CASE_AUDIT.md`, not re-verified | no per-item test |
| R23 | 8 | Out-of-scope list | n/a | n/a | — | — |
| R24 | 10 | Demo: monolith → graph → diagnosis → improvement → tests | 🟡 | 🟡 | see R11 | no scripted demo, no saved report |
| R25 | 11 | Deliverables 01–04 | 🟡 | n/a | 01 `README.md`, `./dev`; 02 `./dev up` (not run); 03 tests exist, no saved run report; 04 `docs/SOLUTION.md` | see priorities |

Percentages (mandatory only, counting R1–R11, R13–R17, R19, R20, R24, R25 = 20): backend 14 ✅ / 6 🟡 / 0 ❌;
frontend (18 applicable, R20/R25 are not UI items) 9 ✅ / 8 🟡 / 1 ❌ (R16). UI never executed in this audit.

## Mockup comparison (slide 4)

The text brief only had a title; the slide is a full mockup.

| Mockup element | Backend | Frontend |
|---|---|---|
| Project file tree (`src/…`) | ✅ `GET /files` | ❌ |
| Block palette (Variable, Condition, Loop, Method, Operation, Return) | ❌ | ❌ |
| Flowchart with typed nodes and problem markers | ✅ | ✅ |
| Analysis panel: Problems / Recommendations / Metrics | ✅ `/findings`, `/complexity` | 🟡 recommendations only |
| «Current O(n²) → after O(n log n)» | ✅ `/optimizations` | ❌ |
| «Expected speedup up to 5.2x» | ✅ measurable via `POST /benchmark` (value must come from measurement, never hardcoded) | ❌ |
| Tabs: Java code / Console / Test results (per-test list) | ✅ | 🟡 code + verify panel |
| Toolbar Import / Export Java / Optimize / Run / Debug | ✅ import, export, optimize, run | 🟡 import, run; ❌ export, optimize, debug |

## Contract mismatches (frontend ↔ openapi)

- `Project` in `apps/web/src/lib/api.ts:1-6` omits `kind` and `file_count` (required in `openapi.yaml`, schema `Project`).
- 13 operations are not consumed by the UI: `/files`, `/files/{path}`, `/complexity`, `/findings`, `/optimizations`
  (+`preview`, `apply`), `/cfg-edits/preview|apply`, `/rollback`, `/revisions/{from}/diff/{to}`, `/benchmark`, `/export`.
- `openapi.yaml` (schema `Project`, field `kind`) says archives reject `/verify`; the `/verify` description and
  `real_archive_project_verify_and_benchmark` say archives are accepted. The schema text is stale.
- The verify report now carries `warnings[]` (`openapi.yaml`, schema `VerifyWarning`); `VerificationReport` in `apps/web/src/lib/api.ts` has no such field and the verify panel does not show it.
- `project-workspace.tsx:737` tells users that runtime is not measured; the API now measures it (`/benchmark`).

## Honest gaps (what a jury can hit)

1. **Reference monolith still has an empty gate.** `Organizer.main` neither reads stdin nor calls `linkTasksToContacts` /
   `linkTasksIndexed` (definitions only, `Organizer.java:405,420`). Verify stays green after any change to them, but
   the report now says so: `change_not_reached` naming the method (asserted in `organizer_workflow_runs_in_the_real_runner`).
   The stdin-driven demo monolith `apps/api/tests/fixtures/demo/Main.java` (copy of `acceptance/Main.java` with deterministic
   output) has a meaningful gate: the mutant tests fail exactly the scenarios that reach the change.
2. **Warnings are advisory.** `behaviorally_verified` is unchanged; reachability is static (calls through interfaces or
   overrides are not expanded, so `change_not_reached` can over-report); there is no coverage measurement.
3. **Runner behaviour:** «both timed out» counts as a failure (`runner.rs:1170`); output that depends on time or randomness
   gives false mismatches.
4. **Complexity and optimizer limits:** a call to unmodelled code makes the method `unknown`; the optimizer only rewrites
   keyed lookups whose enclosing loop cannot change the collection or keys, and now also accepts `equals()` with the outer
   receiver for `String`/boxed/enum keys (extra assumption `outer_key_non_null`) and constructors/setters that only store
   fields of a freshly allocated object.
5. **Stale-JAR gate:** the e2e JAR-freshness check fails after a merge until the JAR is rebuilt.
6. **Not verified:** the web build, the browser flow.

## Priorities

**P0** — saved demo report (scripted upload → optimize → verify → benchmark); show `warnings[]`, optimizations and benchmark in the UI.
Done: stdin-driven demo monolith with mutant tests, user constructors / `String +=` / Euclid in the analyzer, verify warnings, `docs/SOLUTION.md`.
**P1** — UI: optimizations (preview/apply with assumptions), benchmark result, export, ZIP upload, `Project.kind`;
per-item Java-scope checklist test.
**P2** — `move_statement` and rollback in UI; decomposition of nested static classes into files (slide 6, optional).

Do not hardcode «5.2x» or any speedup: it is a number from the case mockup, and results must come from `/benchmark`.
