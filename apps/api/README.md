# Java Blueprints backend

The backend is a single-process Axum API with guest-cookie sessions, local
file-backed Java projects, optimistic source revisions, JDT analysis/CFG access,
and one AST-backed transformation: `replace_condition`.

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
```

That endpoint returns the nested CFG object unchanged, including parser node
IDs, edge fields, labels, reachability, and UTF-16 source ranges. Both endpoints
return `404 analysis_not_found` until the current source revision is analyzed.

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

Swagger UI is served at <http://127.0.0.1:3000/swagger-ui/> and the generated
OpenAPI document at <http://127.0.0.1:3000/api/v1/openapi.json>.

## Scope

Implemented: health, guest sessions, single `.java` projects, owned metadata and
source access, revision-checked source updates, external analysis, CFG listing
and retrieval, and `replace_condition`. If no real worker JAR is configured,
analysis and transformation return `503 analyzer_unavailable`.

Not implemented: arbitrary graph edits, other AST operations, Java execution,
behavioral equivalence, verification, or export routes. Static analysis validates
syntax and semantic diagnostics; it does not establish behavioral equivalence.
