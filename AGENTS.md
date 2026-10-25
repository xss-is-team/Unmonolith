# Java Blueprints — agent instructions

## Project

Hackathon project for the Axenix case «Java Blueprints»: a web tool that loads a ~1000-line Java
monolith, parses it into AST + per-method CFG + project-level dependency graph, diagnoses algorithmic
(Big O) defects with recommendations, lets the user edit logic (condition replacement via ASTRewrite),
regenerates Java as a new revision, and proves behavior preservation by running baseline vs candidate
revisions in an isolated Docker runner.

## Key documents (read before touching the matching area)

- Original case: `Визуальная среда программирования.pdf` (repo root, 12 slides).
  Plain-text extraction: **`docs/case-brief.md`** — use it instead of processing the PDF.
- Compliance status: **`docs/CASE_COMPLIANCE.md`**; session handoff: **`docs/PI_HANDOFF.md`**.
- Backend spec + contract: `apps/api/BACKEND_SPEC.md`, `apps/api/openapi.yaml`.
- Parser CLI/JSON contract: `apps/parser/README.md`. Runner: `apps/runner/README.md`.
- Serena memories (`.serena/memories/`, entry `core.md`) are optional notes, not a dependency —
  no Serena MCP required; some are stale, verify against code.

## Architecture (one repo, four components)

| Path | Role |
|---|---|
| `apps/api/` | Rust/Axum: guest sessions, projects, optimistic revisions, analysis orchestration, SSE, transform, Docker-verify. Owns all API contracts. |
| `apps/parser/` | Java 21 + Eclipse JDT CLI: JSON AST, diagnostics, per-method CFG, Big O analysis, project graph; `transform` subcommand (ASTRewrite, `replace_condition` only). |
| `apps/runner/` | Minimal Docker image (JDK 21) that compiles and runs a revision; created per verify call. |
| `apps/web/` | Next.js 16 App Router UI (React Flow CFG + project graph, CodeMirror editor, revision diff, verify panel, Project Overview tab); proxies `/api/v1/*` to Axum. |

## Commands (repo root; toolchain via `nix develop` or host tools — see README.md)

```sh
./dev up              install web deps, build parser + API, start API :3000 + web :3001
./dev api-check       cargo fmt --check + cargo check
./dev api-test        cargo test
./dev web-fast-check  tsc + eslint (no build)
./dev web-check       tsc + eslint + next build  (fresh checkout: run `pnpm build` in apps/web first)
./dev parser-build    mvn package (fat JAR -> apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar)
./dev runner-build    docker build -t java-blueprints-runner:java21 apps/runner
./dev runner-test     real-container verification test (needs Docker + built image)
```

Opt-in integration tests (API): `cargo test -- --ignored` with
`BLUEPRINTS_REAL_JAR=apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar`, `BLUEPRINTS_REAL_JAVA=$(command -v java)`,
`BLUEPRINTS_REAL_RUNNER_IMAGE=java-blueprints-runner:java21`.
Parser tests: `cd apps/parser && mvn test`.

Note: under `nix develop`, pnpm needs `NPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS=false` (the `dev`
script already sets it); bare `cargo`/`java`/`mvn` are not on PATH outside the Nix shell.

## Git safety

- Never switch branches, commit, push, reset, or discard changes. Work on the current branch (`main`).
- Preserve all user changes; keep diffs minimal and focused; no drive-by refactors.

## Component boundaries (do not cross)

- Rust owns HTTP, sessions, storage, contracts, worker invocation — it never parses Java.
- JDT owns AST, semantics, CFG, Big O analysis, and source rewriting — never a second parser in Rust.
- Web owns rendering and layout (CFG and project graph via React Flow) — Rust never emits layout.
- Uploaded Java executes only inside the Docker runner, never in the API/analysis processes.

## Contracts that must not break

- Parser CLI: `java -jar parser.jar <file.java>` → exactly one JSON doc on stdout (`protocol_version 1`),
  exit 0 parsed / 1 failed / 2 usage; `transform --input --operation --output` subcommand.
- API: routes per `apps/api/openapi.yaml`; guest-cookie sessions; per-project optimistic revisions
  (stale `PUT source` → 409); transform commits a validated new revision; `verify` compares baseline vs
  candidate on identical stdin fixtures (stdout/stderr/exit code); keep utoipa schemas in sync.
- Missing parser JAR → `analyzer_unavailable`; missing runner image → `runner_unavailable`. Never fabricate results.

## Testing and evidence

- Targeted tests after changes: `cargo test` (API), `mvn test` (parser), `pnpm build` (web).
- End-to-end reference: `apps/parser/src/test/resources/acceptance/Organizer.java` (~1000-line monolith)
  exercised by `AcceptanceTest`.
- Claims must cite test names/files; README statements are not evidence.

## Exploration hygiene

- Routine searches exclude: `legacy/` (pinned Commons Math 3.6.1 corpus — never modify),
  `node_modules/`, `target/`, `.next/`, build outputs.
- `explore`/`review` are read-only responsibilities of the current session: no automatic subagents,
  plugins, packages, or extensions, ever.
- For the case PDF use `docs/case-brief.md`.

## Image inspection (frontend)

- Allow reading screenshots, UI references, and browser captures.
- Use images only for frontend visual inspection and comparison.
- Use screenshots to check layout, alignment, spacing, typography, and visual regressions.
- Prefer DOM inspection and browser diagnostics for functional testing.
- Do not generate or modify images unless explicitly requested.
- Do not attach unnecessary screenshots to conversations.
- Keep images small to minimize context usage.

## Known limitations (detail in docs/CASE_COMPLIANCE.md)

- Edit operations: `replace_condition`, `move_statement`, lookup-loop → Map optimization, rollback. No block
  add/remove/drag, no method extraction. The web UI exposes only `replace_condition` and free text editing.
- Responsibility decomposition (case slide 6, optional) is not implemented.
- Runtime before/after measurement exists in the API (`POST /benchmark`), not in the UI.
- Multi-file (ZIP) projects are supported by the API; `/source` and `/transform` are single-file only
  (`409 multi_file_project` for archives); `/verify` accepts archives (`main_class`, `source_level`). The UI uploads only `.java`.
- Big O is a static estimate; methods calling unmodelled code are `unknown`.
