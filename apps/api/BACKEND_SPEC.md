# Java Blueprints — Rust MVP Backend Specification (v0.1)

## 0. Purpose and boundaries
Build a minimal Rust/Axum API to support a Java visual programming tool. Case requirement: import Java, inspect AST/CFG, edit Java ↔ graph, export Java, identify defects/Big-O issues, and run comparable tests of original versus transformed code. The first milestone **does not** implement the entire case. It lays the working API foundation and connects a Java/JDT worker when one is available.

Official brief's initial scale is about 1,000 lines in a Java monolith. Support for 40k lines, collaboration and advanced security tools is explicitly deferred.

### Ownership
- Rust/Axum: HTTP, guest sessions, project persistence, optimistic revision control, worker process invocation, output validation, result delivery, OpenAPI.
- Java/JDT: parsing, bindings, semantic errors, CFG, AST-aware changes and generation, diagnostics/complexity. Never implement a second Java parser in Rust.
- Browser: display and layout of nodes/edges.
- Future sandbox runner: compile and run original/modified Java with identical fixtures.

## 1. Delivery milestones (in order)
**M1 — Foundation (FIRST CODEX TASK)**
- Initialize one Rust package (not a workspace of many crates), Cargo.lock, minimal `flake.nix`, and `.gitignore`.
- Axum service with `/api/v1/healthz`, `/api/v1/openapi.json`, `/swagger-ui/`.
- Guest sessions by random 256-bit token in HttpOnly cookie, with separate on-disk project ownership; implement `POST /api/v1/sessions/guest`.
- `POST /api/v1/projects` accepts one `.java` file via multipart upload, persists bytes in local project workspace, returns project metadata and revision = 1. Project-size limit configurable (suggest 2 MiB initial); reject unsupported archives in M1.
- `GET /api/v1/projects/{project_id}`, `GET .../source`, `PUT .../source` with expected revision (409 on mismatch). Enforce ownership on every route.
- Create `JavaAnalyzer` adapter interface as one concrete struct, wrapping `tokio::process::Command` and safe argv; no Java worker implementation unless the repository already has one. If missing, return `503 analyzer_unavailable`, not mock successful analysis.
- `POST /api/v1/projects/{project_id}/analyze`: invoke configured worker when present, return parsed, validated JSON response; otherwise explicit 503. Configured timeout and error handling; do not spawn a shell.
- Minimal tests for unauthorized access, upload validation, revision conflict, and worker failure; HTTP OpenAPI via utoipa.
- Do not implement `/cfg`, transformations, verification or export yet, although their future contracts may be documented in `openapi.yaml`.

**M2 — Java analysis and CFG integration**
- Connect real JDT worker contract (below).
- Expose method listing and CFG method endpoint, with AST source ranges and diagnostic positions. Java worker builds CFG, frontend renders it.
- Missing bindings/unsupported syntax produce explicit partial diagnostics.

**M3 — Round trip editing**
- Implement only a few explicit AST-backed operations, e.g. `replace_expression`, `replace_condition`. User-supplied graph edges must not be trusted as valid Java.
- Java worker updates AST/source, Rust records a new source revision, and reanalysis returns new CFG. Export updated `.java`.

**M4 — Quality gate**
- Separate strongly isolated runner; no execution in Axum/Java analysis worker.
- Verify original and candidate using the same test cases/inputs, fixed timeout and stdout/stderr/exit-code comparison. Report tested scenarios only, never claim formal equivalence.

**M5 — Case completeness**
- Extend covered Java subset to the mandatory categories (core syntax, java.lang, console IO, java.util, exceptions), improve Big-O diagnostics and recommendations, and demonstrate end-to-end coverage on an organizer-style monolith.

## 2. Persistence without DB
Data lives under `BLUEPRINTS_DATA_DIR` (default `./data`):
```
data/
  sessions/<SHA256(random-cookie-token)>/projects/<UUID>/
    project.json
    revisions/1/Main.java
    revisions/2/Main.java
    analysis/<revision>.json
```
- Paths are constructed server-side using validated UUIDs and fixed filenames, never client-supplied filesystem paths.
- Session token is opaque and high entropy; cookie attributes `HttpOnly`, `SameSite=Lax`, `Secure` in HTTPS deployments. Implement Origin/CSRF checks for cookie-authenticated state changes in production deployment.
- Create session explicitly with `POST /sessions/guest`; frontend sends credentials on subsequent calls.
- Store session cookie hash in directory structure; do not log token or source. No auth email/register in MVP.
- Use atomic writes (temporary file then rename). Serialize write operations for a project to avoid races; reject outdated expected revision.
- A local filesystem is single-instance storage; no multi-instance deployment until persistence changes.

## 3. Project/analysis contracts
`openapi.yaml` is the public source-of-truth design. Implement only milestone-scoped routes; do not expose nonfunctional handlers as successful responses.
- Import: one file as multipart key `file` (file name metadata only; normalize to `Main.java` or safe basename).
- `GET /source`: Java source as `text/plain; charset=utf-8`, `X-Source-Revision` response header.
- `PUT /source`: `application/json` with `{source, expected_revision}`; return new revision on success.
- Analysis returns `{revision, status, methods, diagnostics}`. Each method has a stable-in-revision identifier; diagnostics have severity, message, source range; CFG is loaded per selected method in M2.
- Detailed AST remains internal; don't ship a whole-tree AST for large projects.

### Java worker protocol, M1 adapter / M2 implementation
- Worker launched as `java -jar <configured-jar> analyze --input <workspace-file> --output <result-json-file>` using argv, with fixed working directory. Never pass user code as a shell expression.
- Worker process receives no execution privileges beyond reading the source and writing its own output. Cap runtime, file size and concurrency; capture bounded stderr for diagnostics.
- Worker writes a single JSON document atomically, `protocol_version: 1`, `status: completed|partial|failed`, `revision`, `methods`, `diagnostics`.
- Rust validates protocol version, output JSON size and shape before returning success. A missing worker or malformed response is an error. No fake `success` response.
- Method CFG response uses `{method_id, nodes:[{id,kind,label,source_range}], edges:[{source,target,kind}]}`.
- Important: don't assume CFG alone can reconstruct Java. Keep source/AST and use AST-aware rewrite for edits.

## 4. Safety
- All uploaded source is untrusted. Java analysis must not run the submitted program, `main`, build scripts or annotation processors.
- Worker invoked with direct arguments; no path traversal, archive extraction, or dependency resolution in M1.
- Runner in M4 must be independently isolated (prefer VM/microVM or locked-down sandbox; no host Docker socket exposed to web API). Avoid claiming plain subprocess/container is a hard security boundary.
- Return truthful partial/unavailable diagnostics; no claim that static analysis proves a program safe.

## 5. Build / DX
- Rust: stable toolchain; `axum`, `tokio`, `serde`, `serde_json`, `uuid`, `tracing`, `utoipa`, `utoipa-swagger-ui`, `tower-http` only if used. For random sessions use OS CSPRNG; avoid insecure `uuid` as session secret.
- Nix: `flake.nix` devShell with Rust toolchain, JDK 21, Maven, git, pkg-config. Keep minimal and avoid rebuilding JDT in Rust.
- `cargo fmt`, `cargo check`, `cargo test`. No postgres, sqlx, Redis, Docker Compose or complex CI for M1.
- Keep modules few: `main.rs`, `api.rs`, `project.rs`, `session.rs`, `analyzer.rs`, `error.rs`. Split only if needed.

## 6. M1 Acceptance criteria
- `nix develop` provides Rust/Java tooling (or `cargo` tools work outside Nix).
- API starts with configurable bind address and data directory.
- Swagger UI and OpenAPI route respond.
- Guest A uploads Java file and gets project, can read/update it; Guest B cannot read it.
- Invalid upload returns 400/413 rather than crashing.
- Updating with stale revision returns 409.
- Analyze without installed Java worker fails explicitly with 503 and documented error code.
- Unit/integration tests and fmt/check pass; implementation remains consistent with OpenAPI implemented subset.

## 7. Explicit non-goals for M1
Postgres, Redis, queues, OIDC, real-time collaboration, ZIP projects, 40k-line optimization, full CFG engine in Rust, inference/LLM calls, Semgrep, SonarQube, Infer, Docker-in-Docker, test execution, automatic refactoring, API client SDK generation, many-crate architecture.
