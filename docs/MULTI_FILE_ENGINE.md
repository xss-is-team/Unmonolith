# Multi-file analysis & transformation engine

Branch `feat/multi-file-engine`. This document is the frontend-facing contract and the evidence
log for each phase. Statements cite the test that proves them; anything not listed is not claimed.
Machine-readable contract: `apps/api/openapi.yaml` (also served at `/api/v1/openapi.json`).

Test commands used for the evidence below (run from `apps/api` / `apps/parser`):

```sh
cargo test -- --test-threads=1      # see "Known test flake" below
mvn test
BLUEPRINTS_REAL_JAR=$PWD/../parser/target/ASTPARSER-1.0-SNAPSHOT.jar \
BLUEPRINTS_REAL_JAVA=$(command -v java) cargo test -- --ignored real_multi_file
```

Known test flake (pre-existing, unrelated to this work): the four `transform_*` API tests write and
exec a shell stub and can fail with "text file busy" when run in parallel; they pass with
`--test-threads=1`.

---

## Phase 1 — ZIP import and file storage

### Project model

`Project` gained two fields (existing clients keep working; old `project.json` files default to
`kind: single_file`, `file_count: 1`):

| field | meaning |
|---|---|
| `kind` | `single_file` (upload of one `.java`) or `archive` (upload of a `.zip`) |
| `file_count` | number of stored files |
| `filename` | Java file name (`single_file`) or archive name (`archive`) |

Storage per revision: `revisions/<n>/<relative path>`. Archive revisions are **full snapshots**
(every revision is independently readable; editing one file copies the others), plus
`manifests/<n>.json`. Single-file projects have no stored manifest; theirs is derived.

### Endpoints

`POST /api/v1/projects` (multipart field `file`) — a filename ending in `.zip` selects archive
import; everything else is the unchanged single-file path.

`GET /api/v1/projects/{id}/files[?revision=N]` →

```json
{
  "revision": 1,
  "files": [
    {"path": "src/main/java/com/acme/Main.java", "kind": "java", "role": "main",
     "source_root": "src/main/java/", "size": 412, "sha256": "…"},
    {"path": "pom.xml", "kind": "resource", "role": "resource", "source_root": null, "size": 10, "sha256": "…"}
  ],
  "skipped": [{"path": "__MACOSX/._pom.xml", "reason": "archive metadata"}]
}
```

`GET /api/v1/projects/{id}/files/{path}[?revision=N]` → `text/plain`, header `X-Source-Revision`.
`PUT /api/v1/projects/{id}/files/{path}` with `{"source": "...", "expected_revision": 1}` →
`Project` with `revision + 1` (stale `expected_revision` → `409 revision_conflict`).
Both work for single-file projects too (`path` = the file name).

`GET/PUT /source`, `/transform`, `/verify` and `GET /revisions/{n}` remain **single-file only**;
on an archive project they return `409 multi_file_project`. (Archive transform/verify arrive with
Phases 4–6.) `GET /revisions` works for both kinds.

### Import rules (all configurable, all explicit)

Rejected with `422` unless noted; the whole import fails, nothing is stored:

| code | cause |
|---|---|
| `invalid_archive` | not a readable ZIP, empty, encrypted entry |
| `unsafe_archive_path` | `..`, absolute path, backslash, drive letter, control chars, empty component, too deep/long |
| `archive_symlink` | symlink entry |
| `duplicate_archive_path` | two entries equal after case-folding, or a path that is both file and directory |
| `unsupported_archive_entry` | anything but `.java` or an inert text/build file (`xml gradle kts properties md txt yml yaml json csv`, `.gitignore`, `gradlew`, `LICENSE`, `README`); lists up to 20 paths |
| `no_java_sources` | no `.java` file |
| `archive_bomb` | an entry with > `BLUEPRINTS_MAX_ARCHIVE_RATIO`:1 expansion (and > 64 KiB) |
| `invalid_encoding` | a stored file is not UTF-8 |
| `413 project_too_large` | archive/entry/file-count/uncompressed-size limits |

`__MACOSX/` and `.DS_Store` entries are not stored but are **listed in `skipped`**. Sizes are
enforced while streaming; declared ZIP sizes are never trusted. Build scripts (`pom.xml`,
`build.gradle`, `gradlew`) are stored as inert text and never executed.

| env var | default | limit |
|---|---|---|
| `BLUEPRINTS_MAX_ARCHIVE_BYTES` | 16 MiB | uploaded archive |
| `BLUEPRINTS_MAX_ARCHIVE_ENTRIES` | 2000 | entries incl. directories |
| `BLUEPRINTS_MAX_ARCHIVE_FILES` | 1000 | stored files |
| `BLUEPRINTS_MAX_PROJECT_BYTES` | 2 MiB | per file (also the single-file upload limit) |
| `BLUEPRINTS_MAX_ARCHIVE_TOTAL_BYTES` | 32 MiB | total uncompressed |
| `BLUEPRINTS_MAX_ARCHIVE_RATIO` | 100 | per-entry compression ratio |

Path length 260 bytes and depth 32 are fixed constants in `archive.rs`.

### Migration notes

* No data migration: existing project directories load unchanged.
* `POST /projects` with a `.zip` named file used to return `400`; it now imports the archive
  (a `.zip` that is not a ZIP returns `422 invalid_archive`).
* `Project` JSON has the two new fields; `ProjectGraphNode`/`CfgMethod` additions are optional.

### Evidence

`archive::tests::*` (11: nested packages, traversal/absolute/backslash/drive paths, case-folded
duplicates, file/dir conflict, unsupported entries, no-java, malformed, limits, bomb, symlink,
UTF-8); API `zip_upload_exposes_all_files_with_paths_and_edits_create_snapshots` (13 files,
paths/roles/roots, edit → revision 2, stale → 409, old revision still readable),
`zip_upload_is_session_owned`, `zip_upload_rejects_malicious_archives_without_creating_projects`,
`single_file_project_supports_files_endpoints`. The existing single-file upload/analysis tests
are unchanged and pass.

---

## Phase 2 — Whole-project JDT analysis

`POST /api/v1/projects/{id}/analyze[?source_level=8|11|17|21]` (and `/analyze/stream`) on an
archive project runs the parser's new `project` subcommand:

```sh
java -jar parser.jar project --root <revision dir> --input request.json   # {"source_level":"8","files":[...]}
```

Source roots are discovered per file (file directory minus its package path); a file whose
directory does not match its package becomes its own root and yields a
`package_directory_mismatch` warning. All files are bound **in one JDT environment**
(`ASTParser.createASTs` with the roots as source path), so types, overloads, inheritance and calls
resolve across files. Nothing is compiled or executed.

### Result shape (persisted as `analysis/<rev>.json`)

```json
{
  "protocol_version": 1, "kind": "project", "status": "completed|partial",
  "language": {"source_level": "8", "supported_source_levels": ["8","11","17","21"],
               "runtime_java": "26", "api_resolution": "running_jvm_class_library", "note": "…"},
  "source_roots": [{"path": "src/main/java", "files": 5}],
  "files": [{"path": "…", "package": "com.acme", "source_root": "src/main/java",
             "status": "completed", "imports": [...], "types": [ …members with cfg & analysis… ]}],
  "methods": [{"id": "com.acme.service.Repo.find(int)", "name": "find",
               "source_range": {"file": "…/Repo.java", "start": 0, "length": 0, "line": 8, "end_line": 11}}],
  "project_graph": {"scope": "project", "nodes": [...], "edges": [...], "unresolved_calls": [...]},
  "external_dependencies": [{"name": "org.apache.commons.lang3.StringUtils", "kind": "import",
                             "reason": "unresolved", "files": ["…/Main.java"]}],
  "diagnostics": [{"code": "…", "severity": "…", "source_range": {"file": "…", …}}],
  "stats": {"files": 5, "methods": 9, "cfgs": 9, "source_roots": 1, "unresolved_imports": 1}
}
```

The existing endpoints read this artifact unchanged: `GET /methods`, `GET /methods/{selector}/cfg`,
`GET /methods/{selector}/analysis`, `GET /graph`.

* **Method references.** `id` is `package.Type.Inner.method(ParamTypeTexts)`, independent of line
  numbers and node numbering, and unique within a project. It is the stable reference across UI
  navigation and revisions as long as the signature is unchanged. `selector` and CFG node ids
  (`f<fileIndex>m<n>.n<k>`) are revision-local.
* **Overloads / call graph.** Edges in `project_graph` come from JDT method bindings, never name
  matching: `Service.lookup(int)` → `Repo.find(int)` and `lookup(String)` → `Repo.find(String)`.
  Edge kinds: `calls`, `constructs`, `extends`, `implements`, `depends_on`, `contains`; nodes and
  ranges carry `file`.
* **Unresolved dependencies.** No classpath is supplied. Unresolvable imports are listed in
  `external_dependencies` plus an `info` diagnostic; calls through them stay in
  `unresolved_calls` — never turned into edges.
* **Imperfect code.** Syntax/semantic errors give `status: "partial"`; every other file is still
  analysed (`syntaxErrorInOneFileKeepsOtherFilesAnalysed`).

### Language levels — measured, not assumed

Experiment (Eclipse JDT Core 3.47.0, run against the installed JAR): `JavaCore.setComplianceOptions`
maps **every** requested level below `1.8` to `1.8` (source = compliance = target = `1.8`), so the
grammar of Java 1.2–1.7 cannot be selected (`enum` stays a keyword, generics/lambda-era syntax is
always accepted). Therefore:

| level | status |
|---|---|
| 8, 17 | required, supported, tested (`java8ProjectExposes…`, `sourceLevelControlsSyntaxAcceptance`) |
| 21 | supported (default; existing single-file behaviour unchanged) |
| 11 | accepted; syntax-level only, not separately tested |
| 1.2 – 1.7 | **rejected** with `unsupported_source_level` (`rejectsUnsupportedSourceLevelsExplicitly`); not claimed |

Source level governs syntax (e.g. `var` is an error at level 8, valid at 17). JDK API availability is
**not** checked against the level: bindings come from the JVM running the parser (the
`COMPILER_RELEASE` option was tried and had no effect in the DOM environment), reported in
`language.api_resolution`. The parser JVM version is independent of the source level.

### Evidence

Parser `ProjectAnalyzerTest` (8): Java 8 multi-file project (5 files, packages, source roots, unique
ids and CFG node ids, ≥ 9 CFGs), cross-file call graph with overload resolution, inheritance across
files, lambda call attribution, unresolved import reporting, level rejection, level-dependent syntax,
package/directory mismatch, one broken file, CLI contract (one JSON document, empty stderr).
API opt-in `real_multi_file_zip_analysis_end_to_end` (real JAR): ZIP → analyze → `/methods` →
`/methods/{selector}/cfg` → `/graph` containing the `Main.main → Repo.find` edge; `source_level=1.4`
→ `400`. Existing `AcceptanceTest` on `Organizer.java` still passes (full `mvn test` green).

Limits: `project` analysis runs with 6× the single-file timeout and 16× the output cap; very large
projects may hit `analyzer_timeout` / `analyzer_invalid_output`.

---

## Phase 3 — complexity engine

Decision, experiments and the benchmark table: `docs/COMPLEXITY_ENGINE.md`. Summary: AProVE (JBC) proves
termination only and COSTA is web-only, so the in-tree engine was kept and extended (binary search,
direct recursion, cross-file callees). `GET /projects/{id}/complexity` and `GET /projects/{id}/findings`
return the separated results. Evidence: parser `ComplexityBenchmarkTest` (11), API `real_multi_file_zip_analysis_end_to_end`.

## Phase 4 — automatic optimization (`nested_scan_to_hashmap`)

Flow (all revision-locked, session-owned):

1. `GET /projects/{id}/optimizations[?source_level=]` — proposals with `applicability`, `preconditions`
   (`satisfied`/`violated` + reason), `assumptions`, `affected_files`, `complexity`.
2. `POST .../optimizations/{proposal_id}/preview` `{"base_revision":1}` — exact unified diff, re-parsed and
   validated, plus `preview_sha256`. Nothing is stored.
3. `POST .../optimizations/{proposal_id}/apply`
   `{"base_revision":1,"preview_sha256":"…","acknowledged_assumptions":["null_and_exception_timing","single_threaded"]}`
   — new revision, re-analysed. Errors: `revision_conflict`, `preview_mismatch` (409),
   `assumptions_not_acknowledged`, `optimization_not_applicable`, `transformation_invalid` (422).
4. Verify with `POST /verify`, benchmark with `POST /benchmark`.
5. `POST /projects/{id}/rollback` `{"base_revision":2,"to_revision":1}` — restores revision 1 as revision 3 (with its analysis).
6. `GET /projects/{id}/revisions/{from}/diff/{to}` — per-file unified diffs.

Rewrite is Eclipse JDT ASTRewrite. Applied only when the scan is a keyed lookup whose key equality is primitive `==`,
enum `==`, or `equals` on `String`/boxed/enum; key expressions are side-effect free; the scanned collection is a
plain variable declared outside the enclosing loop and nothing in that loop can modify it or the keys; control flow
is plain (trailing `break` → first match via `putIfAbsent`; none → `Map<K, List<E>>` in iteration order).
Otherwise the proposal is `not_applicable` with the violated precondition (floating-point `==`, reference `==`,
outer-value `equals` receiver, unknown calls, field writes, collection growth, labeled flow, extra work in the scan).
Not provable statically, hence acknowledged by the caller: null/throwing elements surface earlier (index build),
and no concurrent modification. HashMap cost is O(1) in expectation, not a worst-case bound.
Evidence: parser `OptimizerTest` (8; compiles and runs original vs rewritten on 300 random inputs, duplicates,
misses; the 976-line `Organizer.java` yields exactly the manual rewrite from the case), API
`real_optimization_workflow_end_to_end`.

## Phase 5 — CFG edit: `move_statement`

`POST /projects/{id}/cfg-edits/preview|apply`, body `{"base_revision":1,"method_id":"Calc.run(int,int)",
"operation":{"type":"move_statement","node_id":"m0.n4","target_node_id":"m0.n2","position":"before"}}` (apply adds
`preview_sha256`). Node ids are those of `GET /methods/{selector}/cfg`. React Flow coordinates are not part of this API.
Both statements must be direct children of the same block. A rejection is `422 move_rejected` with
`details.reasons[]` (`data_dependency`, `control_flow`, `exception_order`, `exception_effects`, `synchronization`,
`different_scope`, `local_type`, `constructor_call`, `labeled_statement`) each naming the statements involved;
other errors: `unsupported_node`, `no_change`, `method_not_found`. Success returns the diff, per-jumped-statement
evidence and `node_mapping` (old CFG node id → new id, matched by kind+text). Evidence: parser
`StatementMoveTest` (9, includes compile-and-run equivalence), API `real_cfg_edit_end_to_end_single_file_and_archive`.

## Phase 6 — runtime benchmark

`POST /projects/{id}/benchmark` (`GET .../benchmark/{report_id}`), e.g.
`{"baseline_revision":1,"candidate_revision":2,"workloads":[{"name":"n=2500","size":2500,"stdin":"2500\n"}],
"warmup_iterations":3,"measured_iterations":15}`. Each revision is compiled in its own container (no network,
1 CPU, 512 MB) and `apps/runner/Bench.java` calls `main` in-JVM warmup+measured times (`System.nanoTime`, fresh
class loader per iteration by default, stdout hashed). The report has separate `static_complexity`, `functional`,
`runtime` sections, `timings` (compile/setup/harness/JVM wall time, not algorithm time), `environment` (image,
limits, JVM facts) and `limitations`. `speedup` appears only if ≥10 iterations, Mann-Whitney p<0.01, effect ≥1.1×,
bootstrap 95% CI excluding 1 and separated interquartile ranges all hold; differing output ⇒ `not_equivalent`.
Real run (Docker, macOS arm64, Linker example, 15 iterations): n=300 2.9× (CI 2.7–3.3), n=1200 7.8× (6.6–8.8),
n=2500 10.7× (10.0–27.8; baseline spread 0.9, flagged), all `faster`, equivalent, static `O(n*m)`→`O(n + m)`.
Evidence: API `benchmark::tests` (10) and `real_optimize_verify_and_benchmark_end_to_end`,
`real_archive_project_verify_and_benchmark`.

## Phase 7 — contract

Every route is in `apps/api/openapi.yaml` and served at `/api/v1/openapi.json`; test
`openapi_yaml_lists_every_served_operation` fails if a served operation is missing from the YAML.
`/verify` now also accepts archive projects (`main_class`, `source_level`).

## Known limits

Archive projects: `/source`, `/transform` stay single-file. Only one optimization rule and one CFG operation.
Space complexity is not estimated. Benchmarks run baseline then candidate sequentially (host noise is possible).
Frontend untouched. Runner image must be rebuilt only if `Dockerfile` changes (the harness is injected at runtime).
