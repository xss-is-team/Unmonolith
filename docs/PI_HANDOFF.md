# PI handoff — Java Blueprints

Date: 2026-10-24. Session scope: repo configuration for Pi + fact-checked case audit. No application
code, contracts, dependencies, or flake.lock were modified. Deliverables: root `AGENTS.md`,
component AGENTS.md tweaks, `docs/case-brief.md`, `docs/CASE_COMPLIANCE.md`, this file.

## Architecture (one repo, four components)

- `apps/api/` — Rust/Axum API: guest-cookie sessions, file-backed projects, optimistic revisions,
  analyzer orchestration, SSE (`analyze/stream`), transform, Docker-based verify. Owns all contracts
  (`openapi.yaml`, utoipa). ~5.3k LOC.
- `apps/parser/` — Java 21 + Eclipse JDT CLI: JSON AST with resolved bindings, diagnostics,
  per-method CFG, Big O analysis (monomial cost model + findings), project-level dependency graph,
  and a `transform` subcommand (`replace_condition` via ASTRewrite). ~7k LOC incl. tests.
- `apps/runner/` — minimal Dockerfile (temurin 21, unprivileged user). API creates a container per
  revision under test. Not a formal sandbox (documented).
- `apps/web/` — Next.js 16 App Router, React Flow + ELK/dagre CFG view, CodeMirror editor,
  revision diff, verify panel, and the Project Overview tab (project graph view with
  source/CFG navigation, plus clearly-labeled demo prototypes under `/demo`).
  Proxies `/api/v1/*` to Axum (same-origin cookie).
- Data flow: `.java` → parser JSON (AST/CFG/analysis/project_graph) → stored per revision →
  UI views → `replace_condition` → validated new revision → `verify` (Docker, baseline vs candidate,
  stdin fixtures, stdout/stderr/exit comparison).

## Branch / worktree status

- Branch `main` @ `e07b383` (merge of `feat/project-graph`), in sync with `origin/main`.
- Working tree clean; single worktree; no stashes. Recent merges: project-graph, frontend
  integration, parser acceptance, case integration, java-static-analysis, java-runner.
- Since this handoff: `feat/project-overview` (Project Overview UI, project graph endpoint,
  demo prototypes) was integrated on top of `origin/main` (`adb3a56`) in the
  `feat/project-overview-integration` worktree/branch — the `GET /projects/{id}/graph`
  contract (200 with `graph: object|null`) survived the merge.

## Key contracts (do not break)

- Parser CLI: `java -jar parser.jar <file.java>` → one JSON doc on stdout, `protocol_version: 1`,
  status `completed|partial|failed`; exit 0/1/2. Transform: `transform --input F --operation OP.json
  --output OUT`; operation `{"method_id","node_id","expression"}`; node resolved by exact source
  range; output written only after reparse + validation.
- API (`/api/v1`): `POST /sessions/guest`; `POST /projects` (multipart `.java`, ≤2 MiB);
  `GET/PUT /projects/{id}/source` (PUT requires `expected_revision`, stale → 409);
  `POST /analyze`, `POST /analyze/stream` (SSE lifecycle; parser emits JSON atomically);
  `GET /revisions`, `/revisions/{n}`; `GET /methods`, `/methods/{sel}/cfg`, `/methods/{sel}/analysis`
  (selector opaque, revision-local); `GET /projects/{id}/graph` (200 `{revision, graph: object|null}`
  — `null` when the parser could not build the graph; 500 `graph_invalid` for a malformed stored
  artifact); `POST /transform` (commits validated new revision);
  `POST /verify` (`baseline_revision`, `candidate_revision`, `test_cases:[{name,stdin}]`) and
  `GET /verify/{report_id}`. Missing JAR → `analyzer_unavailable`; missing image →
  `runner_unavailable`. Never fabricate analysis/verification results.
- Boundaries: Rust never parses Java; JDT never executes user code; web never computes layout in
  Rust; execution only inside Docker.

## Test results (this audit, via `nix develop` toolchain)

- API: `cargo test` → 36 passed, 0 failed, 2 ignored (opt-in).
- Opt-in with real deps (parser fat JAR built via `mvn -o -DskipTests package`; Docker daemon up,
  image `java-blueprints-runner:java21` present): `real_ast_rewrite_http_end_to_end` PASS,
  `real_container_http_verification` PASS.
- Parser: `mvn test` → 94 passed (Acceptance 13 incl. 976-line `Organizer.java` monolith,
  Analysis 17, Cfg 24, Main 7, ProjectGraph 2, Semantic 12, Transform 19).
- Web: `pnpm install --frozen-lockfile` OK, `eslint` clean, `next build` OK, `tsc --noEmit` OK
  after build (fresh-clone tsc-before-build fails — see limitations).

## Active limitations

- One edit operation only (`replace_condition`); no block move/add/remove, no method extraction.
- No runtime before/after performance measurement (static Big O estimate + recommendation only).
- Responsibility decomposition (case P6, optional) not implemented.
- P7 mandatory-scope coverage is implicit (acceptance fixture), not a formal per-construct test matrix.
- Case «technical document» (full construct list, P7) absent from repo; slide 4 mockup is
  graphics-only (not comparable without image processing — out of scope here).
- Stale docs — resolved by the `feat/project-overview` integration: `apps/parser/README.md` and
  `.serena/memories/tech_stack.md` now correctly describe complexity analysis, transformations
  and the project graph.
- `./dev web-check` ordering: bare `tsc` fails on fresh checkout until `next build` generates
  Next 16 global types (e.g. `LayoutProps`) into `.next/types`.

## Next three tasks

1. Submission evidence: scripted demo on `Organizer.java` — upload → analyze → `replace_condition`
   → `verify` with real stdin cases → save verify report JSON (case P11 deliverables 3+4).
2. P7 scope checklist tests in `apps/parser` (one test per mandatory construct family) to make
   «mandatory Java scope» provable (case P7, top evaluation axis).
3. **Done (integration):** expose `project_graph` — read-only `GET /projects/{id}/graph` from the
   stored artifact + the Project Overview UI tab (parser work already existed and is tested).

## References

- Case (12 slides): `Визуальная среда программирования.pdf`; text: `docs/case-brief.md`.
- Compliance matrix, risks, priorities: `docs/CASE_COMPLIANCE.md`.
- Backend spec/milestones: `apps/api/BACKEND_SPEC.md` (M1–M5; M5 «case completeness» is the
  remaining case-driven milestone); OpenAPI: `apps/api/openapi.yaml`.
- Parser contract: `apps/parser/README.md`; runner: `apps/runner/README.md`; web: `apps/web/README.md`.
- Agent rules: root `AGENTS.md` (+ `apps/api/AGENTS.md`, `apps/web/AGENTS.md`).
- Corpus (never modify): `legacy/commons-math-3.6.1/` (see its README-test-fixture.md).
