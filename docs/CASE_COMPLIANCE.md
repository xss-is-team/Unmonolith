# Case compliance — Java Blueprints

Date: 2026-10-25 · Branch `main` @ `53f5484` (multi-file engine merged).
Method: requirement-by-requirement audit of the case PDF (12 slides, read in full including slide 4 mockup
graphics) against source code and test runs. Backend and frontend are rated separately.
Status: ✅ done · 🟡 partial · ❌ missing · ❓ not verified. Evidence kinds are kept apart:
**code** (file reference), **test** (name), **run** (executed in this audit), **claimed** (docs only).

## Runs behind this document

| Run | Result |
|---|---|
| `cargo test` (apps/api) | lib 67 passed, 7 ignored. The three HTTP e2e files fail with «JAR is older than the parser sources» (`apps/api/tests/common/mod.rs:49`) when the JAR mtime is older than the sources (happens right after a merge/checkout). Rebuild with `./dev parser-build`. |
| same with a fresh-mtime copy of the same JAR (`BLUEPRINTS_REAL_JAR`) | `e2e_analysis` 6/6, `e2e_edit` 13/13, `e2e_import` 6/6 |
| `cargo test --test docker_e2e -- --ignored` (real Docker + runner image) | 7/7 passed |
| `cargo test --lib -- --ignored` (7 `real_*` tests, real JAR + JDK + Docker) | 7/7 passed |
| Parser `mvn test` | **not run** (no `mvn` on the audit machine). `target/surefire-reports` show 161 tests, 0 failures (Acceptance 13, Analysis 20, Cfg 24, ComplexityBenchmark 11, LegacyJava 28, Main 7, Optimizer 8, ProjectAnalyzer 8, ProjectGraph 2, Semantic 12, StatementMove 9, Transform 19), newer than the JAR; not confirmed by a run. |
| Web `tsc` / `eslint` / `next build` | **not run** (no `pnpm`, no `node_modules`). Every frontend status below is from reading code. |
| Parser CLI on `acceptance/Organizer.java` and `acceptance/Main.java` | run directly (see R13/R16) |

## Requirement matrix

Mandatory = case slides 2, 3, 5, 6 (gate), 7, 10, 11. Optional = slide 6 decomposition, slide 8 bonus.

| ID | Slide | Requirement | Backend | Frontend | Evidence | Gap |
|---|---|---|---|---|---|---|
| R1 | 2 | Web tool for a ≈1000-line monolith | ✅ | 🟡 ❓ | test `e2e_analysis::organizer_monolith_workflow_still_works_end_to_end` (976 lines) | UI not run |
| R2 | 2 | Program understood as code and as graph | ✅ | ✅ | `CfgBuilder.java`, `ProjectGraph.java`, `CfgTest`, `ProjectGraphTest`; UI mounts both viewers (`project-workspace.tsx:500,513`) | — |
| R3 | 2 | Helps improve the program | ✅ | 🟡 | `Optimizer.java`, `GET /optimizations`; run: 1 proposal on Organizer, 2 on `acceptance/Main.java` | UI shows only text recommendations |
| R4 | 2 | **Key contract**: tests confirm behavior preserved | 🟡 | 🟡 | `runner.rs` `compare_observations` (stdout, stderr, exit code, timeout, overflow), `docker_e2e` 7/7 | Gate is only as strong as the scenarios; see Honest gaps |
| R5 | 3 | 01 Load `.java` | ✅ | ✅ | `single_file_uploads_keep_working_unchanged`, ZIP `e2e_import` 6/6; `upload-panel.tsx` | UI accepts `.java` only |
| R6 | 3 | 02 Parse | ✅ | ✅ | JDT with bindings; `SemanticTest`, `MainTest` (surefire) | — |
| R7 | 3 | 03 Build graph | ✅ | ✅ | CFG + project graph | methods without body have no CFG |
| R8 | 3 | 04 Explore and edit, «Java ↔ блок-схема» two-way | 🟡 | 🟡 | `replace_condition`, `move_statement`, optimizations, rollback: `e2e_edit` 13/13, `TransformTest`, `StatementMoveTest` | no block add/remove/drag, no method extraction; UI exposes only `replace_condition` + text editing |
| R9 | 3 | 05 Assemble Java | ✅ | ✅ | ASTRewrite → validated new revision; `real_ast_rewrite_http_end_to_end` | — |
| R10 | 3 | 06 Run and compare | ✅ | ✅ | `real_container_http_verification`, `docker_e2e` | — |
| R11 | 3 | Mandatory finish: full path | 🟡 | 🟡 | `real_optimize_verify_and_benchmark_end_to_end`, `organizer_workflow_runs_in_the_real_runner` | path runs, but verify is empty on Organizer; UI lacks the optimize step |
| R12 | 4 | UI concept (mockup) | 🟡 | 🟡 | see «Mockup comparison» | no block palette, Debug, metrics tab, optimize/export buttons |
| R13 | 5 | Complexity of method/fragment | ✅ | ✅ | `ComplexityAnalyzer.java`; run: Organizer 30/103 methods `unknown`, Main 12/67 | frequent `unknown` |
| R14 | 5 | Defect + risk + recommendation | ✅ | ✅ | findings `repeated_collection_scan`, `nested_collection_iteration`; `cfg-viewer.tsx:416-423` | risk is inside `explanation`; two finding kinds |
| R15 | 5 | Code line and graph node | ✅ | ✅ | `source_range`, `node_ids`; `cfg-viewer.tsx:220,436-444` | — |
| R16 | 5 | Fix with expected effect | 🟡 | ❌ | `/optimizations` (`method_before/after`, `scan_before/after`), `POST /benchmark` | run: on `acceptance/Main.java` the slide-5 method `mergeDuplicateItemsQuadratic` gets `method_before: "unknown"` (user constructor has no cost model, `ComplexityAnalyzer.java:1749`); UI shows no effect |
| R17 | 5 | Diagnosis verifiable | 🟡 | 🟡 | `confidence`, `assumptions`, `limitations`, honest `unknown`; benchmark verdicts | some patterns not modelled |
| R18 | 6 | Auto decomposition (optional) | ❌ | ❌ | — | not started |
| R19 | 6 | Quality gate: build + tests = preserved | 🟡 | 🟡 | `/verify` compiles and runs both revisions | see R4 |
| R20 | 7 | Mandatory Java scope 1–4 | 🟡 | n/a | `AcceptanceTest::coversMandatoryLanguageConstructs`, `LegacyJavaCompatibilityTest` | no per-item checklist test |
| R21 | 7 | Case technical document | ❌ | n/a | — | not in repo; organizers' artifact |
| R22 | 8 | Bonus: record, **enum**, generics, lambda, `java.util.function`, Stream | 🟡 | n/a | parsed/compiled in acceptance fixtures; CFG loss for enum-constant bodies / anonymous classes claimed in `docs/CASE_AUDIT.md`, not re-verified | no per-item test |
| R23 | 8 | Out-of-scope list | n/a | n/a | — | — |
| R24 | 10 | Demo: monolith → graph → diagnosis → improvement → tests | 🟡 | 🟡 | see R11 | no scripted demo, no saved report |
| R25 | 11 | Deliverables 01–04 | 🟡 | n/a | 01 `README.md`, `./dev`; 02 `./dev up` (not run); 03 tests exist, no saved run report; 04 `docs/SOLUTION.md` | see priorities |

Percentages (mandatory only, counting R1–R11, R13–R17, R19, R20, R24, R25 = 20): backend 11 ✅ / 9 🟡 / 0 ❌;
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
- `project-workspace.tsx:737` tells users that runtime is not measured; the API now measures it (`/benchmark`).

## Honest gaps (what a jury can hit)

1. **Empty verify on the reference monolith.** `Organizer.main` neither reads stdin nor calls
   `linkTasksToContacts`/`linkTasksIndexed` (only definitions at `Organizer.java:405,420`), so any change to the
   optimized methods keeps verify green. Established by static search; the mutant was not executed in this audit.
2. **Better demo monolith unused.** `acceptance/Main.java` (801 lines, stdin menu, slide-5 defect) is not
   referenced by any test, and its `merge_inventory` prints elapsed milliseconds (`Main.java:690-693`), which makes
   output nondeterministic.
3. **Runner behaviour:** «both timed out» counts as a failure (`runner.rs:1170`); a scenario set of empty stdin is
   accepted without any warning.
4. **Complexity:** a call to any unmodelled code makes the method `unknown`; user-defined constructors are `unknown`.
5. **Stale test gate:** the e2e JAR-freshness check fails after a merge until the JAR is rebuilt.
6. **Not verified:** the web build, parser `mvn test`, the browser flow.

## Priorities

**P0** — demo whose verify is meaningful (stdin-driven monolith that reaches the optimized methods, plus a mutant
test that must fail); user constructors as O(1) when provably so; verify warnings for empty scenarios / unreachable
changes; saved demo report; `docs/SOLUTION.md` (done).
**P1** — UI: optimizations (preview/apply with assumptions), benchmark result, export, ZIP upload, `Project.kind`;
per-item Java-scope checklist test.
**P2** — `move_statement` and rollback in UI; decomposition of nested static classes into files (slide 6, optional).

Do not hardcode «5.2x» or any speedup: it is a number from the case mockup, and results must come from `/benchmark`.
