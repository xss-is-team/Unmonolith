# Java Blueprints — Rust MVP Backend Specification (v0.1)

## 0. Purpose and boundaries
Build a minimal Rust/Axum API to support a Java visual programming tool. Case requirement: import Java, inspect AST/CFG, edit Java ↔ graph, export Java, identify defects/Big-O issues, and run comparable tests of original versus transformed code. The first milestone **does not** implement the entire case. It lays the working API foundation and connects a Java/JDT worker when one is available.

Official brief's initial scale is about 1,000 lines in a Java monolith. Support for 40k lines, collaboration and advanced security tools is explicitly deferred.

### Ownership
- Rust/Axum: HTTP, guest sessions, project persistence, optimistic revision control, worker process invocation, output validation, result delivery, OpenAPI.
- Java/JDT: parsing, bindings, semantic errors, CFG, AST-aware changes and generation, diagnostics/complexity. Never implement a second Java parser in Rust.
- Browser: display and layout of nodes/edges.
- Isolated Docker runner: compile and run stored revisions with identical fixtures; never execute uploaded Java in the API host process.

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

### Current implemented slice

M1 is complete. The API now also implements the M2 CFG read path, the single
M3 operation `replace_condition`, and the separate M4 revision verification
operation. Verification does not gate transformations. The parser CFG is nested on each method/constructor member in
`types[].members[]` (including nested types); the flat root `methods[]` list is
only a summary and is not the CFG source.

## 2. Persistence without DB
Data lives under `BLUEPRINTS_DATA_DIR` (default `./data`):
```
data/
  sessions/<SHA256(random-cookie-token)>/projects/<UUID>/
    project.json
    revisions/1/<safe-upload-basename>
    revisions/2/<safe-upload-basename>
    analysis/<revision>.json
    verification/<report-id>.json
```
- Paths are constructed server-side using validated UUIDs and a validated upload basename, never a client-supplied filesystem path.
- Session token is opaque and high entropy; cookie attributes `HttpOnly`, `SameSite=Lax`, `Secure` in HTTPS deployments. Implement Origin/CSRF checks for cookie-authenticated state changes in production deployment.
- Create session explicitly with `POST /sessions/guest`; frontend sends credentials on subsequent calls.
- Store session cookie hash in directory structure; do not log token or source. No auth email/register in MVP.
- Use atomic writes (temporary file then rename). Serialize write operations for a project to avoid races; reject outdated expected revision.
- A local filesystem is single-instance storage; no multi-instance deployment until persistence changes.

## 3. Project/analysis contracts
`openapi.yaml` is the public source-of-truth design. Implement only milestone-scoped routes; do not expose nonfunctional handlers as successful responses.
- Import: one file as multipart key `file`; preserve only its safe basename for metadata and revision storage.
- `GET /source`: Java source as `text/plain; charset=utf-8`, `X-Source-Revision` response header.
- `PUT /source`: `application/json` with `{source, expected_revision}`; return new revision on success.
- `GET /projects/{project_id}/revisions` and `GET .../revisions/{revision}` expose only published, owned source revisions for text comparison; they do not reinterpret parser artifacts.
- Analysis returns `{revision, status, methods, diagnostics}`. Each method has a stable-in-revision identifier; diagnostics have severity, message, source range.
- `POST /projects/{project_id}/analyze/stream` runs the same analyzer as the synchronous endpoint and emits truthful lifecycle events. The current JDT CLI returns its parser result atomically, so this wrapper does not invent per-method partial results.
- `GET /projects/{project_id}/methods` reads only `analysis/<current revision>.json`, traverses nested AST members, and returns methods/constructors with CFGs plus an opaque revision-local URL selector.
- `GET /projects/{project_id}/graph` returns the top-level parser `project_graph` unchanged in a `{revision, graph}` response from the owned current-revision artifact. M1 graph scope is the uploaded compilation unit; `project_graph: null` returns `422 project_graph_unavailable`.
- `GET /projects/{project_id}/methods/{selector}/cfg` returns the selected nested parser CFG unchanged. CFG node and source-range offsets are UTF-16 code units.
- `GET /projects/{project_id}/methods/{selector}/analysis` returns the selected method's nested parser `analysis` in a trusted current-revision envelope. Complexity, fragments and findings are not recomputed in Rust; their CFG node IDs and UTF-16 source ranges remain unchanged.
- Detailed AST remains internal; don't ship a whole-tree AST for large projects.

### Java worker protocol, M1 adapter / M2 implementation
- Worker launched as `java -jar <configured-jar> <absolute-workspace-file>` using argv, with a fixed working directory. Never pass user code as a shell expression.
- Worker process receives no execution privileges beyond reading the source. Cap runtime, captured stdout/stderr size and concurrency; drain both streams concurrently.
- Worker writes exactly one UTF-8 JSON document to stdout with `protocol_version: 1`, `status: completed|partial|failed`, `methods`, `diagnostics`, and its internal AST data. It does not receive or emit the project revision.
- Rust validates the exit code, protocol version, output JSON size and shape, attaches the trusted project revision, and atomically stores the full parser result under `analysis/<revision>.json`. A missing worker or malformed response is an error. No fake `success` response.
- Method CFG response uses the worker's exact shape: `{method_id, entry, exit, nodes:[{id,kind,statement_kind?,text?,source_range,reachable?}], edges:[{from,to,kind,label?,back?}]}`.
- Important: don't assume CFG alone can reconstruct Java. Keep source/AST and use AST-aware rewrite for edits.

### Java worker transform protocol

- Rust invokes `java -jar <jar> transform --input <absolute source> --operation <absolute JSON> --output <absolute output>` with direct argv. The positional analysis CLI is unchanged.
- The operation JSON is `{method_id, node_id, operation:{type:"replace_condition", expression}}`. `base_revision` remains Rust-owned concurrency metadata. Rust validates the revision, method and branch node against that revision's stored analysis before invocation.
- A successful worker exits zero, writes the transformed UTF-8 Java source to the output file, and emits a JSON document with `status: "completed"` on stdout. A rejected change exits nonzero with `{"status":"failed","error":{"code":"...","message":"..."}}`.
- Rust uses an isolated temporary directory, bounded stdout/stderr, kill-on-drop and the configured analyzer timeout. It reparses the candidate and rejects partial analysis or a missing selected method CFG.
- The accepted source and complete new-revision analysis artifact are written before atomically advancing project metadata. Transformations share the source-write lock, so concurrent requests based on the same revision cannot both commit.

### Isolated Java runner protocol

- `POST /projects/{project_id}/verify` accepts two existing owned revisions and 1-20 named UTF-8 stdin fixtures. It does not mutate either revision or change transform semantics. Completed comparison and compilation-failure reports are atomically persisted under `verification/<report-id>.json`; `GET /projects/{project_id}/verify/{report_id}` retrieves an owned report.
- The runner image is optional configuration. If it or the Docker-compatible CLI/daemon is unavailable, the API returns `503`; it never runs the submitted program on the API host.
- Each revision is compiled under its stored Java filename with fixed Java 21 settings. Compilation failure, timeout, output overflow and a missing conventional `main` are represented in the verification report and prevent case execution.
- Containers use a non-root numeric user, no network, a read-only root filesystem, writable size-limited tmpfs work areas, CPU/memory/PID/fd limits, dropped capabilities and `no-new-privileges`. No host paths are mounted. Source is copied into tmpfs over a bounded stdin pipe.
- Cases receive only predefined UTF-8 stdin and fixed JVM options. stdout bytes, stderr bytes, exit code, timeout and overflow states are compared exactly; a timeout or overflow fails the case because the complete behavior was not observed. A matching report applies only to the supplied fixtures and is not proof of equivalence.
- `behaviorally_verified` is true only for a complete `passed` report covering every supplied non-empty fixture. Empty fixture sets are rejected, compilation/mismatch reports set it false, and runner/infrastructure errors return an error without persisting a successful report.
- Docker is risk reduction, not a formal sandbox. Public untrusted use requires stronger VM/microVM isolation and operational controls.

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
