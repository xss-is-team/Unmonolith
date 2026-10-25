# Case compliance — Java Blueprints

Date: 2026-10-25 · Branch `wip` on top of `main` @ `53f5484`. Updated after the verify-warnings, demo-monolith and complexity/optimizer work (uncommitted at the time of writing).
Method: requirement-by-requirement audit of the case PDF (12 slides, read in full including slide 4 mockup
graphics) against source code and test runs. Backend and frontend are rated separately.
Status: ✅ done · 🟡 partial · ❌ missing · ❓ not verified. Evidence kinds are kept apart:
**code** (file reference), **test** (name), **run** (executed in this audit), **claimed** (docs only).

## Runs behind this document

| Run | Result |
|---|---|
| `cargo test` (apps/api), fresh JAR built by `mvn package` | lib 76 passed (7 ignored); `e2e_analysis` 6/6, `e2e_decompose` 5/5, `e2e_edit` 13/13, `e2e_import` 6/6. The e2e files refuse to run when the JAR is older than `apps/parser/src` (`apps/api/tests/common/mod.rs:49`): rebuild with `./dev parser-build` after any pull. |
| `cargo test --test docker_e2e -- --ignored` (real Docker, runner image) | 13/13 passed. Run with `BLUEPRINTS_REAL_RUNNER_IMAGE=<image id>`: resolving the image by tag failed inside the test process on the audit machine although the same `docker image inspect` worked in a shell. |
| `cargo test --lib -- --ignored` (7 `real_*` tests, real JAR + JDK + Docker) | 7/7 passed |
| Parser `mvn test` (Maven 3.9.9, JDK 26 with `release 21`) | 215 tests, 0 failures (Acceptance 13, Analysis 27, Cfg 24, ComplexityBenchmark 11, Decomposer 6, MethodGroups 3, StringBuilderRule 2, JavaScopeChecklist 27, LegacyJava 28, Main 7, Optimizer 14, ProjectAnalyzer 8, ProjectGraph 2, Semantic 12, StatementMove 9, Transform 19) |
| Web `tsc` / `eslint` / `next build` | **not run** (no `pnpm`, no `node_modules`). Every frontend status below is from reading code. |
| Parser CLI on `acceptance/Organizer.java` and `acceptance/Main.java` | run directly (see R13/R16) |

## Requirement matrix

Mandatory = case slides 2, 3, 5, 6 (gate), 7, 10, 11. Optional = slide 6 decomposition, slide 8 bonus.

| ID | Slide | Requirement | Backend | Frontend | Evidence | Gap |
|---|---|---|---|---|---|---|
| R1 | 2 | Web tool for a ≈1000-line monolith | ✅ | 🟡 ❓ | test `e2e_analysis::organizer_monolith_workflow_still_works_end_to_end` (976 lines) | UI not run |
| R2 | 2 | Program understood as code and as graph | ✅ | ✅ | `CfgBuilder.java`, `ProjectGraph.java`, `CfgTest`, `ProjectGraphTest`; UI mounts both viewers (`project-workspace.tsx:500,513`) | — |
| R3 | 2 | Helps improve the program | ✅ | 🟡 | `Optimizer.java` (nested lookup → HashMap), `StringBuilderRule.java` (`String +=` in a loop → `StringBuilder`), `GET /optimizations`; `StringBuilderRuleTest` (output of the rewritten program is identical) | UI: findings, proposals with preconditions and assumptions, preview diff, apply |
| R4 | 2 | **Key contract**: tests confirm behavior preserved | ✅ | ✅ | `runner.rs` `compare_observations` (stdout, stderr, exit code, timeout, overflow), `docker_e2e` 13/13; mutants are caught only by scenarios that reach them (`demo_monolith_mutants_are_caught_only_by_scenarios_that_reach_them`); weak runs are flagged by `warnings[]` (`verify_warns_when_the_result_proves_little`, `verify_hints.rs`); repeated runs separate non-determinism from a real change (`repeats`, status `unstable`) and scenarios may carry expectations (`repeated_runs_tell_nondeterminism_from_a_behaviour_change_and_expectations_are_checked`); the report lists `not_covered` | UI shows `warnings[]`, `not_covered`, repeats and expectations; yet; scenarios are still user-supplied |
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
| R16 | 5 | Fix with expected effect | ✅ | ✅ | `/optimizations` (`method_before/after`, `scan_before/after`), `POST /benchmark`; the slide-5 method `mergeDuplicateItemsQuadratic` is `O(n^2)`, applicable, optimized and verified in Docker (`demo_monolith_automatic_optimization_is_verified_on_scenarios_that_reach_it`) | UI shows the scan before → after in the proposal and the measured speedup in the benchmark tab; `reconcileOrdersWithTransactions` stays refused (mutates existing elements) |
| R17 | 5 | Diagnosis verifiable | ✅ | 🟡 | `confidence`, `assumptions`, `limitations`, honest `unknown`; benchmark verdicts; `String +=` in a loop is `medium` with finding `string_concat_in_loop`, Euclid is `O(log n)` (`AnalysisTest`, `AcceptanceTest.classifiesComplexityOfTheFixtureMethods`) | recursion, streams and unmodelled library calls stay `unknown` |
| R18 | 6 | Auto decomposition into responsibilities (optional) | ✅ mechanical · 🟡 semantic | ✅ panel «Разбор монолита» (types → files, method groups → classes, preview diff, apply) | `Decomposer.java` (member types → files), `MethodGroups.java` (groups of static methods → classes: `parse*`→`Parsing`, `format*`→`Formatting`, private helpers and constants travel along, calls and method references re-pointed), `GET/POST /projects/{id}/decomposition[/preview|/apply]` (`classes` or `groups`); `DecomposerTest` (6), `MethodGroupsTest` (3: behaviour of the split program is identical, refusal on shared private state), `e2e_decompose` (5), real Docker `decomposed_monolith_behaves_like_the_original_and_can_then_be_optimized_in_its_own_file` (original vs 17-file project: 6/6 scenarios identical, no warnings; then the slide-5 method is optimized inside `DataOptimizer.java` and verified again) | splits by member type, it does not recognise «Parser/Analyzer/Optimizer»; refuses types that use private or enclosing-class members; UI missing |
| R19 | 6 | Quality gate: build + tests = preserved | 🟡 | 🟡 | `/verify` compiles and runs both revisions | see R4 |
| R20 | 7 | Mandatory Java scope 1–4 | ✅ | n/a | `JavaScopeChecklistTest.slide7Sections1To4AndSlide8Bonus`: one dynamic test per slide-7 bullet (20 bullets, names in the test report). Each fragment compiles with `javac --release 21`, parses with no error and no `unknown` node, gives every method with a body a CFG and an analysis, contains the named AST kinds and resolves the named JDK types; plus `AcceptanceTest::coversMandatoryLanguageConstructs`, `LegacyJavaCompatibilityTest` | proves parsing and analysis, not execution (execution is the Docker e2e suite); the case's own technical document is still missing (R21) |
| R21 | 7 | Case technical document | ❌ | n/a | — | not in repo; organizers' artifact |
| R22 | 8 | Bonus: record, **enum**, generics, lambda, `java.util.function`, Stream | ✅ parse/CFG · 🟡 depth | n/a | the five bonus bullets in `JavaScopeChecklistTest`; methods inside anonymous classes and enum-constant bodies have **no CFG and are not listed** (verified), now reported as info diagnostic `unanalyzed_anonymous_body` (`methodsInAnonymousClassesAndEnumConstantBodiesAreReportedAsUnanalyzed`) | lambdas and streams are kept as expression text only: no CFG inside them, complexity of such methods is `unknown` |
| R23 | 8 | Out-of-scope list | n/a | n/a | — | — |
| R24 | 10 | Demo: monolith → graph → diagnosis → improvement → tests | 🟡 | 🟡 | `scripts/demo.py` (`./dev demo`) runs the whole path through the HTTP API and stores real responses in `docs/evidence/demo/` | UI cannot yet drive the optimize/benchmark steps; the demo monolith is our own, not the organizers' |
| R25 | 11 | Deliverables 01–04 | 🟡 | n/a | 01 `README.md`, `./dev`; 02 `./dev up` (not run); 03 tests exist and `docs/evidence/demo/` holds a saved real run (verify + benchmark); 04 `docs/SOLUTION.md` | see priorities |

Percentages (mandatory only, counting R1–R11, R13–R17, R19, R20, R24, R25 = 20): backend 15 ✅ / 5 🟡 / 0 ❌ (R18 is optional and not counted);
frontend: the UI now covers analysis, Big O overview, optimization preview/apply, verify (repeats, expectations, warnings), benchmark, decomposition, revision diff, export; the real-backend Playwright suite (`apps/web/e2e/real-backend.spec.ts`) passes 3/3. Missing: block palette/drag-and-drop, debug, rollback screen.

## Mockup comparison (slide 4)

The text brief only had a title; the slide is a full mockup.

| Mockup element | Backend | Frontend |
|---|---|---|
| Project file tree (`src/…`) | ✅ `GET /files` | ✅ |
| Block palette (Variable, Condition, Loop, Method, Operation, Return) | ❌ | ❌ |
| Flowchart with typed nodes and problem markers | ✅ | ✅ |
| Analysis panel: Problems / Recommendations / Metrics | ✅ `/findings`, `/complexity` | ✅ findings, Big O overview, per-method complexity |
| «Current O(n²) → after O(n log n)» | ✅ `/optimizations` | ✅ in the proposal |
| «Expected speedup up to 5.2x» | ✅ measurable via `POST /benchmark` (value must come from measurement, never hardcoded) | ✅ benchmark tab |
| Tabs: Java code / Console / Test results (per-test list) | ✅ | ✅ code, verify with per-scenario list, benchmark, revision diff |
| Toolbar Import / Export Java / Optimize / Run / Debug | ✅ import, export, optimize, run | ✅ import, export, optimize, run, decomposition; ❌ debug |

## Contract mismatches (frontend ↔ openapi)

The frontend client (`apps/web/src/lib/api.ts`) covers the optimization, benchmark, decomposition (`classes` and `groups`), export, files and revision-diff endpoints and the new verify fields (`repeats`, `expected_*`, `warnings`, `not_covered`, status `unstable`).
Not consumed: `POST /rollback` has a client function but no screen; `GET /graph` is used by the overview only.

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
4. **Silent-looking gap, now surfaced:** anonymous-class and enum-constant bodies are not analyzed (no CFG, no complexity); the parser emits an informational `unanalyzed_anonymous_body` diagnostic per body. The editor will show these as `info` entries (e.g. the reference monolith has several).
5. **Decomposition limits:** two modes: (a) direct static member types of a top-level class move into files; (b) groups of static methods of one class (grouped by the first word of the name) move into a new class, with private helpers/constants that only they use. Types that use private members or members of the enclosing class by simple name stay (a qualified use of a non-private member is fine), as do types another file refers to; the split is by type, not by meaning, and the orphaned section-banner comments of the monolith stay in the host. A method group is refused when it needs private state that other code also uses, an inherited member, or is referenced by a static import. A single-file project turns into an archive project, so `/source` and `/transform` close for it (use `/files`); the UI handles this.
6. **Complexity and optimizer limits:** a call to unmodelled code makes the method `unknown`; the optimizer only rewrites
   keyed lookups whose enclosing loop cannot change the collection or keys, and now also accepts `equals()` with the outer
   receiver for `String`/boxed/enum keys (extra assumption `outer_key_non_null`) and constructors/setters that only store
   fields of a freshly allocated object.
7. **Stale-JAR gate:** the e2e JAR-freshness check fails after a merge until the JAR is rebuilt.
8. **Verified:** the web build (`pnpm build`), `tsc`, `eslint` and the browser flow (`pnpm exec playwright test`, 3/3 against the real API, parser and Docker runner). Not verified: a deployed public instance, other browsers.

## Priorities

**Done:** UI for warnings, optimizations, benchmark, export, ZIP upload, decomposition; verify repeats/expectations; `StringBuilder` rule; method-group decomposition.
**Done too:** `List.contains` → `HashSet` rule (`ContainsToSetRule`), Markdown report (`GET /projects/{id}/report`, «Отчёт» button), demo monolith whose scenarios reach every rule (saved run: three optimizations, verify 8/8, decomposition), `compose.yaml` (`docker compose up --build`).
**Next:** block insert and extract method in the UI (high risk); verify a deployed public instance; R21 (the case technical document) is an organizers' artifact and is not in the repo.

Do not hardcode «5.2x» or any speedup: it is a number from the case mockup, and results must come from `/benchmark`.
