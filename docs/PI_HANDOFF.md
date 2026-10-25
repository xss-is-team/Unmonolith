# Session handoff — Java Blueprints

Date: 2026-10-25. State: `main` @ `53f5484` (PR #15 `feat/multi-file-engine` merged). Docs were refreshed to this
state; no application code, contracts or dependencies were changed in that pass.

## Architecture (one repo, four components)

- `apps/api/` — Rust/Axum: guest-cookie sessions, file-backed single-file and ZIP projects, optimistic revisions,
  analysis orchestration (+SSE), transform, optimizations, CFG edits, rollback, export, Docker verify and
  benchmark. Owns the contract (`openapi.yaml`, utoipa). It never parses Java: it runs the parser JAR and the
  runner container only.
- `apps/parser/` — Java 21 + Eclipse JDT CLI. Subcommands: default (JSON AST, diagnostics, CFG, Big O, project
  graph), `transform` (`replace_condition`), `edit` (`move_statement`), `optimize` (discover/apply lookup → Map),
  `project` (multi-file analysis).
- `apps/runner/` — JDK 21 image; a container per verify/benchmark call (`--network none`, read-only FS, dropped
  capabilities, CPU/memory/pids limits; not a formal sandbox).
- `apps/web/` — Next.js 16: CodeMirror editor, React Flow CFG + project graph, revision diff, verify panel.
  Uses only the older single-file endpoints. Frontend work is in progress separately.

Data flow: upload `.java`/ZIP → parser JSON stored per revision → CFG/graph/analysis views → edit
(`replace_condition` / `move_statement` / optimization) → new validated revision → `verify` (baseline vs
candidate, identical stdin cases) → optional `benchmark`.

## Contracts that must not break

- Parser CLI: `java -jar parser.jar <file.java>` → exactly one JSON document (`protocol_version 1`), exit 0/1/2.
- API: routes per `apps/api/openapi.yaml`; stale `PUT source` → 409; archives reject `/source` and `/transform` with
  `409 multi_file_project`; `/verify` accepts archives. Missing JAR → `analyzer_unavailable`, missing image →
  `runner_unavailable`; no host-side execution, no fabricated results.

## Test state (see `docs/CASE_COMPLIANCE.md` for what was actually run)

- API: lib 67 passed; e2e 6 + 13 + 6; Docker e2e 7; `real_*` 7 (all run on 2026-10-25).
- Parser: 161 tests per surefire reports; `mvn test` was not run in the last audit.
- Web: not built in the last audit.
- e2e suites refuse to run if the JAR is older than `apps/parser/src`: run `./dev parser-build` after any pull.

## Known limitations

See «Known limitations» in `AGENTS.md` and the gaps in `docs/CASE_COMPLIANCE.md`. Most important: verify on
`Organizer.java` is content-empty (its optimized methods are unreachable from `main`); optimizations, benchmark,
`move_statement`, rollback, export and ZIP are not in the UI; decomposition (slide 6, optional) is absent.

## Next tasks

1. Demo monolith with a meaningful gate (stdin-driven, reaches optimized methods) + mutant test that must fail.
2. User-defined constructors as O(1) in `ComplexityAnalyzer` (slide-5 method on `acceptance/Main.java` is `unknown`).
3. Verify warnings (empty scenarios, unreachable changes); scripted demo with a saved report in `docs/evidence/`.
4. UI wiring of the backend features (frontend team).

## References

- Case: `Визуальная среда программирования.pdf` (slide 4 is a mockup; `docs/case-brief.md` has only text).
- Solution description: `docs/SOLUTION.md`. Compliance: `docs/CASE_COMPLIANCE.md`. Backlog: `docs/TODO.md`.
- Engine: `docs/MULTI_FILE_ENGINE.md`, `docs/COMPLEXITY_ENGINE.md`, `docs/E2E_TESTS.md`.
- `docs/CASE_AUDIT.md` is an untracked earlier draft; verify its claims against code before relying on them.
- Corpus (never modify): `legacy/commons-math-3.6.1/`.
