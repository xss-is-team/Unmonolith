# Java Blueprints backend

The backend is a single-process Axum API with guest-cookie sessions, local
file-backed Java projects, optimistic source revisions, JDT analysis, CFG and
compilation-unit graph access, and one AST-backed transformation:
`replace_condition`.

## Development

From the repository root, use `./dev api` to run the API, `./dev api-check` for formatting and compile checks, and `./dev api-test` for tests.

By default it listens on `127.0.0.1:3000` and stores state in `./data`.
Configuration uses these environment variables:

- `BLUEPRINTS_BIND` (default `127.0.0.1:3000`)
- `BLUEPRINTS_DATA_DIR` (default `./data`)
- `BLUEPRINTS_MAX_PROJECT_BYTES` (default `2097152`)
- `BLUEPRINTS_SECURE_COOKIES` (default `false`; set to `true` behind HTTPS)
- `BLUEPRINTS_ANALYZER_JAR` (unset by default)
- `BLUEPRINTS_JAVA_BIN` (default `java`)
- `BLUEPRINTS_ANALYZER_TIMEOUT_MS` (default `10000`)
- `BLUEPRINTS_ANALYZER_OUTPUT_BYTES` (default `2097152`; maximum captured parser stdout)
- `BLUEPRINTS_RUNNER_IMAGE` (unset by default; for example `java-blueprints-runner:java21`)
- `BLUEPRINTS_CONTAINER_BIN` (default `docker`)
- `BLUEPRINTS_RUNNER_COMPILE_TIMEOUT_MS` (default `10000`)
- `BLUEPRINTS_RUNNER_CASE_TIMEOUT_MS` (default `2000`, per test case)
- `BLUEPRINTS_RUNNER_OUTPUT_BYTES` (default `65536`, independently for stdout and stderr)

The configured parser is invoked directly as
`java -jar <BLUEPRINTS_ANALYZER_JAR> <absolute-source-path>`. It must emit one
UTF-8 JSON document to stdout. The API validates the result, attaches the
trusted source revision, and keeps the complete parser JSON in the project's
per-revision analysis artifact while returning the public analysis summary.
Uploaded Java files retain only their safe basename so JDT filename diagnostics
match the source the user supplied.

CFGs are not rebuilt in Rust. `GET /api/v1/projects/{project_id}/methods` walks
the complete current-revision parser artifact and lists method and constructor
members whose nested `cfg` is available. Its `selector` (`m0`, `m1`, ...) is an
opaque, revision-local, URL-safe value for:

```text
GET /api/v1/projects/{project_id}/methods/{selector}/cfg
GET /api/v1/projects/{project_id}/methods/{selector}/analysis
```

`GET /api/v1/projects/{project_id}/graph` returns the parser's top-level
`project_graph` unchanged in `{ revision, graph }`. It uses the same owned,
current-revision analysis artifact as the method endpoints. The graph is still
limited to the uploaded compilation unit; it is not a whole-repository graph.
If the parser reports `project_graph: null`, the endpoint returns 200 with
`graph: null`; missing or stale analysis remains an explicit `404` or `409`
response, and a stored graph with an unexpected shape is a `500` (`graph_invalid`).

The CFG endpoint returns the nested CFG object unchanged, including parser node
IDs, edge fields, labels, reachability, and UTF-16 source ranges. The analysis
endpoint returns a revision envelope around the parser's exact nested
`analysis` object: its complexity estimate, loop fragments and findings. Any
fragment/finding `node_ids` refer to the same revision's CFG, and its source
ranges remain UTF-16. These endpoints return `404 analysis_not_found` until the
current source revision is analyzed; a method without analysis returns
`404 method_analysis_not_found`.

`POST /api/v1/projects/{project_id}/analyze/stream` invokes that same analyzer
and emits SSE lifecycle events. The current parser returns its complete JSON
atomically, so the stream reports real start/progress/completion or failure but
does not fabricate per-method partial results. Owned published revisions are
listed by `GET .../revisions`; `GET .../revisions/{revision}` returns stored
source for the UI's text diff without interpreting historical parser JSON.

The transform endpoint accepts only this contract:

```http
POST /api/v1/projects/{project_id}/transform
Content-Type: application/json

{
  "base_revision": 1,
  "method_id": "Example.process(int)",
  "node_id": "m1.n2",
  "operation": {
    "type": "replace_condition",
    "expression": "count >= 0"
  }
}
```

Rust verifies ownership, the current revision, method ID, and branch node ID,
then invokes the configured JAR without a shell:

```text
java -jar <jar> transform --input <absolute-source-path> --operation <operation-json-path> --output <absolute-output-path>
```

The operation file contains `method_id`, `node_id`, and `operation` from the HTTP
request; `base_revision` is Rust-owned concurrency metadata and is not sent to
the Java worker. On success the worker writes UTF-8 Java source to the output
path, exits zero, and returns a JSON document with `status: "completed"`. On a
rejected transformation it exits nonzero and returns
`{"status":"failed","error":{"code":"...","message":"..."}}`. The API bounds
stdout/stderr, enforces the configured timeout, and uses a per-request temporary
directory.

Before committing, Rust runs the unchanged positional analysis CLI against the
candidate. Only a `completed` parse with semantic diagnostics free of errors and
the selected method CFG is accepted. Source and full analysis are written as the
next revision before project metadata is atomically advanced. Failures leave the
current revision unchanged; concurrent writes are serialized and stale requests
return `409 revision_conflict`.

## Isolated behavioral verification

Build the Java 21 image and enable it explicitly:

```bash
docker build -t java-blueprints-runner:java21 apps/runner
BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21 ./dev api
```

`POST /api/v1/projects/{project_id}/verify` compares two existing revisions with
the same named UTF-8 stdin fixtures. Both revisions are compiled with
`javac -encoding UTF-8 --release 21` under their original Java filename. The
runner locates the resulting top-level class and requires a conventional
`public static void main(String[] args)` before executing any cases.

For every revision, the API creates an ephemeral container using direct Docker
arguments. It runs as UID/GID 65532, has no network, a read-only root filesystem,
writable size-limited `/work` and `/tmp` tmpfs filesystems, dropped capabilities,
`no-new-privileges`, 0.5 CPU, 256 MiB memory/swap, a 64 PID limit and a 64-file
descriptor limit. Source bytes enter `/work` through the stdin of `dd`; project
storage and other host paths are never mounted. Compilation and each execution
have separate timeouts and bounded stdout/stderr. Containers are forcibly
removed after success and failure, with a drop-time cleanup fallback.

Example request:

```json
{
  "baseline_revision": 1,
  "candidate_revision": 2,
  "test_cases": [
    {"name": "positive", "stdin": "5\n"},
    {"name": "zero", "stdin": "0\n"},
    {"name": "negative", "stdin": "-3\n"}
  ]
}
```

The synchronous report is atomically persisted under the owned project and can
be retrieved with
`GET /api/v1/projects/{project_id}/verify/{report_id}`. It contains its UUID,
creation time, requested-case count, both compilation results and one baseline
and candidate observation per executed case. Observations compare stdout bytes,
stderr bytes, exit status, timeout state and output-overflow state. Streams have
an optional exact UTF-8 value plus base64 bytes, so non-UTF-8 output remains
unambiguous. `behaviorally_verified` is true only when the runner returns a
complete `passed` result for every supplied non-empty fixture; it remains false
for mismatches and compilation failures. Missing fixtures and runner failures
are request/infrastructure errors, never successful verification. A passing
report applies only to its fixtures and is not a claim of program equivalence.
A timeout or output overflow makes that case fail even when both revisions hit
the same limit, because complete behavior was not observed. Compilation or missing-main failures return a
structured `compilation_failed` report with zero executed cases. Missing Docker,
image, or container lifecycle failures return an explicit `503` and never fall
back to host Java execution.

The request is limited to 20 uniquely named cases, 64 KiB stdin per case and
256 KiB combined stdin. Single-file and archive (ZIP) projects are verified through this
route (archives compile all files together and need `main_class`/`source_level`); archives reject only the
single-file `/source` and `/transform` with `409 multi_file_project`. No custom JVM flags, classpaths, dependencies, Maven or Gradle.

Run the real-container test after building the image:

```bash
BLUEPRINTS_REAL_RUNNER_IMAGE=java-blueprints-runner:java21 \
  cargo test api::tests::real_container_http_verification -- --ignored --exact --nocapture
```

Docker materially reduces risk but is not a formally secure sandbox. Before
public untrusted exposure, operate the runner on a dedicated host and add a
stronger VM or microVM boundary, daemon-level policy and production monitoring.

Swagger UI is served at <http://127.0.0.1:3000/swagger-ui/> and the generated
OpenAPI document at <http://127.0.0.1:3000/api/v1/openapi.json>.

## Scope

Implemented (routes and schemas in `openapi.yaml`): health, guest sessions, single `.java` and ZIP
(multi-file) projects with file tree and per-file edits, revision-checked updates and history, rollback,
revision diff, external analysis (+ SSE lifecycle), method list/CFG/analysis, project graph, project-wide
complexity and findings, `replace_condition`, optimization discovery/preview/apply (lookup loop -> Map),
decomposition discovery/preview/apply (static member types -> files of their own; a single-file project becomes an archive project),
CFG `move_statement` preview/apply, runtime benchmark with honest verdicts, export, and persisted isolated
behavioral verification of two stored revisions. If no real worker JAR is configured, analysis and
transformation return `503 analyzer_unavailable`; if no runner image is configured, verification and
benchmark return `503 runner_unavailable`.

Not implemented: drag/add/remove of arbitrary blocks, method extraction, semantic recognition of responsibilities (decomposition is a mechanical split by member type),
formal behavioral equivalence, external Java dependencies (unresolved calls are reported, never guessed),
Maven/Gradle import. Static analysis and fixture-based verification do not establish behavioral equivalence.
