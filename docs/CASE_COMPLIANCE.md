# Case compliance — Java Blueprints

Date: 2026-10-24 · Branch: `main` @ `e07b383` (clean tree, in sync with `origin/main`)
Method: requirement-by-requirement audit of `docs/case-brief.md` (extracted from
`Визуальная среда программирования.pdf`, 12 slides) against actual source code and test runs.
Status legend: **Done** (implemented + test evidence) · **Partial** · **Missing** · **Unverified**.

Updated 2026-10-24 after the `feat/project-overview` integration: the project graph endpoint
(`GET /projects/{id}/graph`) and the Project Overview tab are now implemented — R1/R5, item 1 of
«Missing or incomplete», priority P1.4, next step 4 and the «stale docs» risk are updated accordingly.

## Requirement matrix

| # | Case ref | Requirement | Status | Implementation | Test evidence | Remaining work |
|---|---|---|---|---|---|---|
| R1 | P2 | Web tool for ≈1000-line Java monolith; program understood as code **and** as graph | Done | `apps/web` (upload panel, workspace), `apps/api` (projects), `apps/parser` (AST+CFG+project graph) | Parser `AcceptanceTest` on 976-line `Organizer.java`; API `upload_*` tests | None — project graph visible in UI (Project Overview tab, see R5) |
| R2 | P2 | Key contract: after any transformation, automated tests confirm behavior preserved | Done | `apps/api/src/runner.rs`, `POST /projects/{id}/verify`, per-case stdout/stderr/exit comparison | API `verify_*` tests (8), opt-in `real_container_http_verification` (real Docker) **passed** | UI should encourage meaningful test cases; reports stored per project |
| R3 | P3 | 01 Load `.java` | Done | `upload-panel.tsx` → `POST /projects` (multipart, 2 MiB limit, basename sanitization) | API upload tests | None (single file per project — matches case) |
| R4 | P3 | 02 Parse code (AST, diagnostics, semantics) | Done | `apps/parser`: JDT AST + resolved bindings, `AstJson.java`, semantic diagnostics | `SemanticTest` (12), `MainTest` (7), partial-status handling | See R12 for scope checklist formalization |
| R5 | P3 | 03 Build graph | Done | Per-method CFG `CfgBuilder.java` (if/switch/while/do/for/enhanced-for/try/break/continue/throw/assert/yield); project-level dependency graph `ProjectGraph.java` (types, calls, ownership, unresolved) | `CfgTest` (24), `ProjectGraphTest` (2) | None — exposed via `GET /projects/{id}/graph` + Project Overview tab |
| R6 | P3 | 04 Explore and edit (two-way sync) | Partial | Source editor (CodeMirror) + optimistic autosave (`project-workspace.tsx`); branch-node condition editing → `replace_condition` via ASTRewrite (`Transform.java`) → new revision; re-analysis on save; revision diff view | Parser `TransformTest` (19); API `transform_*` tests; opt-in `real_ast_rewrite_http_end_to_end` **passed** | Only ONE edit operation (condition). No block move/add/remove, no method extraction; graph-side editing limited to branch conditions |
| R7 | P3 | 05 Assemble Java | Done | ASTRewrite output committed as new source revision (validated, reparsed, diagnostics-checked) | Same as R6 | Broader operations would strengthen (R6) |
| R8 | P3 | 06 Run and compare | Done | Docker runner compiles baseline + candidate, runs identical stdin cases, compares observations | `real_container_http_verification` **passed**; failure/timeout/overflow paths tested | Scenario quality depends on user-supplied cases (see risks) |
| R9 | P5 | Big O defect diagnosis: complexity + defect + risk + recommendation; code line + graph node + explanation + fix with expected effect; verifiable diagnosis | Done (static) | `ComplexityAnalyzer.java`: monomial cost model over size variables, `O(...)` rendering, findings `repeated_collection_scan` (warning) and `nested_collection_iteration` (info) with message/explanation/recommendation/source_range/node_ids | `AnalysisTest` (17), `AcceptanceTest` | No runtime before/after measurement — "expected effect" is static (Big O only), no timings |
| R10 | P6 | Monolith decomposition into responsibilities (Parser/Analyzer/Optimizer/Main) | Missing | — (explicitly optional in case: «ПРИ НЕОБХОДИМОСТИ») | — | Not started; would be a model-level refactor operation |
| R11 | P7 | Mandatory Java scope: (1) core language + java.lang, (2) console I/O (Scanner, Formatter, String.format/printf, StandardCharsets), (3) java.util collections (List/Set/Map + impls, Iterator, Collections, Arrays, Comparator, Optional, *.of), (4) exceptions (checked/unchecked, throws/catch, custom via inheritance) | Partial | JDT parses all Java syntactically; bindings resolved against JDK 21; CFG covers the required control flow; acceptance monolith exercises classes/interfaces/records/enums, generics, collections, exceptions, switch, loops, Scanner | `AcceptanceTest` (13) over `Organizer.java` (976 lines, Russian strings incl. Cyrillic) | No systematic checklist test per P7 item (e.g., Formatter, Comparator, Optional, List.of, custom checked exceptions untested as explicit cases); the case's «technical document» with the full construct list is **not in the repo** |
| R12 | P8 | Bonus scope (record, generics, lambdas, java.util.function, Stream API/Collectors) | Partial | Records/generics/lambdas parse & run (JDK 21 image, JDT); seen in fixture (record `Note`) | `AcceptanceTest` | No explicit Stream API verification |
| R13 | P8 | Out-of-scope boundaries (concurrency, reflection, files, network, java.time not required) | Done (n/a) | No support claimed; runner image is plain JDK | — | None |
| R14 | P9 | AI/libraries freely allowed; organizer-prepared monoliths | n/a (rule) | Uses JDT, React Flow, Axum, Next — allowed | — | None |
| R15 | P10 | Final demo: full path on one example (load → visualize → change → generate → run → compare) | Unverified | All steps exist end-to-end at API level; UI wiring present (`project-workspace.tsx` verify panel, CFG viewer, transform) | API-level e2e tests passed (R2/R6/R8) | No scripted demo run captured; browser session not executed during this audit (see risks) |
| R16 | P11 | Deliverable 1: source repo + startup instructions | Done | `README.md` (host + Nix install, `./dev up`), component READMEs | Manual: `./dev` commands verified runnable via nix shell | Keep fresh with code |
| R17 | P11 | Deliverable 2: web demo (link or reproducible build) | Done | `pnpm build` succeeds (Next 16, routes `/`, `/projects/[projectId]`) | `next build` **passed** this audit | Deploy/hosting for the deadline |
| R18 | P11 | Deliverable 3: behavior check — automated tests + their run results | Partial | verify pipeline + tests exist | verify tests passed | Need a captured demo report (verify JSON for a real monolith scenario) as submission evidence |
| R19 | P11 | Deliverable 4: solution description (architecture, supported scope, limitations) | Partial | Root `README.md` (architecture, data flow, components) | — | Dedicated solution doc with explicit scope/limitations (this report helps; add `docs/SOLUTION.md`) |
| R20 | P4 | Web interface concept (mockup slide) | Unverified | Real IDE-like workspace implemented | — | Cannot compare against the mockup: slide 4 is graphics-only and image processing is out of scope for this session |

Mandatory vs optional: **mandatory** — R1–R9, R11, R15–R19. **Optional** — R10 (decomposition), R12 (bonus scope). Evaluation axes (P10): scope, behavior preservation, diagnosis/optimization usefulness, UI clarity, implementation/demo quality.

## Verified functionality (executed during this audit)

- **API (Rust):** `cargo test` → 36 passed, 0 failed, 2 ignored.
- **Opt-in integration (real deps):** `real_ast_rewrite_http_end_to_end` and
  `real_container_http_verification` (Docker daemon + prebuilt `java-blueprints-runner:java21`) → both **passed**.
- **Parser (JDT):** `mvn test` → 94 passed (Acceptance 13, Analysis 17, Cfg 24, Main 7, ProjectGraph 2, Semantic 12, Transform 19).
- **Web:** `pnpm install --frozen-lockfile` OK, `eslint` clean, `next build` OK, `tsc --noEmit` OK **after** build.
- Toolchain obtained from the project's own `flake.nix` via `nix develop` (no tools installed).

## Missing or incomplete

1. **Project-level graph exposure** — **resolved** in the `feat/project-overview` integration:
   `GET /api/v1/projects/{id}/graph` (200 `{revision, graph: object|null}` — `null` when the
   parser could not build the graph; 404/409 per contract; 500 `graph_invalid`) plus the
   Project Overview tab (`project-graph-viewer.tsx`).
2. **Editing breadth** — one operation (`replace_condition`). The case's «исследовать и править»
   and the podcast's «перетащить блоки» imply broader block-level editing (move/add/remove,
   extract method). Not implemented.
3. **Runtime performance measurement** — none. Diagnosis gives static Big O + recommendation
   (satisfies P5 wording «ожидаемый эффект» only statically; the podcast's 5.2× speedup example
   would need timings, which the PDF does not explicitly require).
4. **Decomposition** (optional P6) — absent.
5. **Formal P7 scope checklist tests** — coverage is implicit via the acceptance monolith; no
   per-item tests for Formatter, Comparator, Optional, `List.of/Set.of/Map.of`, custom checked
   exceptions, `String.format/printf`, StandardCharsets round-trip.
6. **Case «technical document»** with the full construct list (referenced on P7) is not in the repo.
7. **Submission evidence** — no captured verify report for a demo monolith; no scripted demo.

## Technical risks

- **Runner is not a formal sandbox** (acknowledged in `apps/runner/README.md`): fine for a local
  hackathon demo; do not expose to untrusted traffic.
- **Verification fidelity = scenario fidelity**: `verify` compares stdout/stderr/exit for
  user-supplied stdin cases; weak/empty cases give a green gate. UI defaults to one empty case —
  guide users (or ship example cases) to make the «quality gate» meaningful.
- **Complexity estimator is method-local and conservative** (no call graph; unknown calls →
  `unknown`). Nested-loop findings trigger on linear-collection loops with a lookup shape; other
  hot patterns (e.g., repeated `contains` on `ArrayList` without a loop) may be missed.
- **Bleeding-edge toolchain**: Next.js 16 + React 19 with build-time-generated global types —
  fresh-clone `./dev web-check` fails at the `tsc` step before `build` has generated
  `.next/types` (ordering issue in `dev` script).
- **Stale docs mislead** — **resolved** in the `feat/project-overview` integration:
  `apps/parser/README.md` and `.serena/memories/tech_stack.md` now correctly describe complexity
  analysis, transformations and the project graph.
- **Single-file scope**: multi-file uploads unsupported by design; acceptable for the case
  («в одном монолите») but the organizers' monoliths must be single-file to be processable.

## Priorities

**P0 — required for a compliant submission**
1. Scripted end-to-end demo on one monolith with a captured verify report (R15/R18): pick
   `Organizer.java`, apply one `replace_condition`, run `verify` with real stdin cases, save the
   JSON report as submission evidence.
2. Formalize P7 scope coverage: add a parser test suite that walks the mandatory-construct
   checklist (R11) so «поддержка обязательного Java scope» is provable, not asserted.
3. Solution description deliverable (R19): `docs/SOLUTION.md` — architecture, supported scope,
   limitations (this report can seed it).

**P1 — high value, moderate cost**
4. **Done (integration):** expose `project_graph` via a read endpoint and a UI tab —
   `GET /projects/{id}/graph` + Project Overview tab (parser work already done & tested).
5. Add 1–2 more edit operations (e.g., `replace_expression`, add/remove statement) to make
   «изменить» credible beyond condition swapping; include before/after complexity in the transform
   UI response (re-analysis already happens on the new revision).
6. Fix `dev` web-check ordering (run build before bare tsc) and refresh stale docs
   (parser README, serena memories).

**P2 — optional / later**
7. Runtime before/after performance measurement for the optimized fragment (timings in the
   runner or a bench harness) to quantify the «expected effect».
8. Responsibility decomposition (P6, optional) as a model-level refactoring operation.
9. Bonus scope verification (Stream API/Collectors) and `ROADMAP.md`.

## Recommended next steps

1. `docs/SOLUTION.md` + fix stale parser README (docs-only, no code risk).
2. Parser: checklist test module for P7 constructs (snippets per item; assert parse/CFG/analysis).
3. Demo: `scripts/demo.sh` — upload Organizer → analyze → replace_condition → verify (stdin cases)
   → emit report JSON + summary; run it and commit the report as evidence.
4. **Done (integration):** `GET /projects/{id}/graph` (read from stored artifact) + Project
   Overview web tab.
