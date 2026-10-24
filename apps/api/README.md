# Java Blueprints backend

The Axum API provides guest-cookie sessions, local file-backed Java projects,
optimistic source revisions, and an external Java/JDT analyzer adapter. M2 adds
per-method CFG retrieval from the immutable analysis artifact for the current
source revision.

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

Swagger UI is served at <http://127.0.0.1:3000/swagger-ui/> and the generated
OpenAPI document at <http://127.0.0.1:3000/api/v1/openapi.json>.

## Scope

Implemented: health, guest sessions, single `.java` upload, owned project and
source routes, revision-checked source updates, analysis, and
`GET /api/v1/projects/{project_id}/methods/{method_id}/cfg`. CFG reads are
session-owned and revision-scoped; absent or unsupported graphs return
`404 cfg_not_found`. Without a configured parser JAR, analysis returns
`503 analyzer_unavailable`.

Transformations, isolated verification, and export remain planned contracts.
