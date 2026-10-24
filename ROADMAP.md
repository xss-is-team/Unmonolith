# Product goals

## North star

An engineer opens a Java project and sees its main flows, can drill into the code behind them, choose a useful modernization, apply it, and review evidence that the result still behaves correctly.

## Product goals

1. **Understand the project:** import a whole Java repository and show the main project flows in familiar flowcharts.
2. **Navigate by detail:** drill down from project flow to module/package, type, and method. These flowcharts explain and navigate; they do not edit source.
3. **Recommend useful work:** surface concrete migrations or improvements with scope, rationale, and affected code. Examples: migrate a legacy monolith from the Java 2 era to a chosen target such as Java 16, or decompose part of that monolith into services. The actual source level must be detected from project configuration or confirmed when it is unknown; “Java 2” is an example era, not a reliable source-level value.
4. **Change business logic through a model:** provide a separate editable BPMN-style process diagram for supported transformations. Use recognizable process notation, but map each supported model edit to an explicit code transformation; do not claim full BPMN support or imply that unsupported elements change code.
5. **Make the result reviewable:** show the source diff, compile and run available checks, compare observable behavior, and report before/after metrics such as measured runtime.
6. **Keep manual edits exceptional:** if a case cannot be transformed safely, point to the exact code and explain the required intervention. Do not make source editing the normal workflow.

Passing tests is evidence for tested scenarios, not proof of identical behavior for every possible input. Runtime comparisons need the same environment and workload.

## Work that can start before backend support

1. **Set the interaction model:** define project → flow → module/package → type → method navigation; distinguish read-only flowcharts, architecture/dependency maps, method CFG, and the editable BPMN-style transformation diagram.
2. **Prototype the flowchart language:** use conventional start/end ovals, process rectangles, decision diamonds, input/output shapes, labeled connectors, and clear hierarchy. A dependency graph is a separate view; do not present it as a business flowchart.
3. **Build a representative frontend fixture:** include nested project areas, types, methods, relationships, source locations, and several modernization suggestions. Mark it as demo data and keep it out of production behavior.
4. **Prototype the action journey:** choose a finding, Java-version migration, or monolith decomposition → inspect rationale, affected code, and proposed service boundaries → try the transformation → review diff → see compile/test/behavior/metric results. Use deterministic fixture outcomes only.
5. **Prototype BPMN-style edits:** allow moving or editing supported model elements, show which transformation they request, and provide local undo/reset. Do not imply that a local diagram edit changed Java.
6. **Design large-project and failure states:** progressive drill-down, search, filters, loading, unsupported constructs, incomplete analysis, and failed transformation or verification.
7. **Keep a typed frontend boundary:** use fixtures behind an adapter so real API data can replace them. Never silently fall back to fixture data when a real request fails.

## BPMN editor direction

- The `/demo` editable process view now uses the `bpmn-js` modeler and creates BPMN 2.0 XML. A `bp:Operation` extension stores typed demo operation IDs and parameters on supported service tasks; diagram geometry does not change source.
- `bpmn-js` is used in the demo, not yet approved for production adoption. Its license requires the bpmn.io watermark to remain visible and unobstructed; the demo preserves it. Production adoption depends on accepting that product constraint or choosing another editor. See the [toolkit](https://bpmn.io/toolkit/bpmn-js/) and [license](https://github.com/bpmn-io/bpmn-js/blob/develop/LICENSE).
- Keep React Flow for project navigation and dependency graphs. Load the BPMN modeler client-side in Next.js; keep transformation preview, apply, diff, build, tests, behavior comparison, and runtime measurement as separate steps.
- Keep transformation requests separate from diagram geometry: moving a shape changes layout only. Each supported BPMN element must carry a typed operation ID and parameters; the backend receives only explicitly supported operations. Keep BPMN editing, transformation preview/apply, diff, build, tests, behavior comparison, and runtime measurement as separate steps.
- Regardless of editor, support only a declared BPMN subset and reject unsupported or ambiguous operations visibly.

## Keep frontend work easy to replace

- Keep API wire types and validation in `apps/web/src/lib/api.ts`; map backend responses into presentation models before passing data to UI components.
- Keep synthetic catalogs under `apps/web/src/fixtures/` and supply them only from the standalone `/demo` route. The production project overview has no fixture imports or implicit demo switch; a failed API request stays an error.
- When backend contracts arrive, add or update the API adapter and connect it to the existing presentation components. Then remove the demo route, fixture catalogs, and fixture-only wiring; do not rename backend fields throughout the UI.
- The flowchart, modernization, and graph views receive typed data as props. `/demo` owns its fixture imports; the production project overview requests its graph only through the API adapter.

These tasks validate the product interaction. They do not deliver real project import, code migration, or behavior guarantees.

## Backend-dependent goals

1. Import a complete repository, detect or request its source and target Java levels, and build a project-aware classpath, including Maven/Gradle dependencies.
2. Expose revisioned project structure and nested behavior-flow data with IDs unique within a revision, file-qualified source ranges, completeness, and unsupported-syntax information. If the UI must track nodes across revisions, expose an explicit identity mapping; parser graph IDs are revision-local.
3. Expose supported migration/refactoring proposals and explicit apply/preview operations with source diffs and rollback through revisions.
4. Compile the transformed project and run its existing checks in an isolated environment.
5. Compare baseline and transformed behavior for supplied scenarios; report limits and failures instead of claiming universal equivalence.
6. Measure before/after runtime under reproducible workloads and show the measurement conditions alongside results.
7. Connect model elements to code transformations, including supported version upgrades and monolith decomposition, so unsupported or ambiguous edits fail visibly without modifying source.

## Milestones

### M0 — Product interaction prototype

- [x] Define the hierarchy and distinguish read-only project flowcharts, the type-dependency view, method CFG, and the editable BPMN-style transformation plan.
- [x] Build nested project-to-operation flowcharts with breadcrumbs and hierarchy search on explicitly labeled demo data; selecting source-linked nodes opens the matching excerpt from a synthetic Java file using parser-shaped source ranges. Keep the parser's type graph as a separate view.
- [x] Demonstrate package → type → method → file navigation on a synthetic multi-file graph fixture, highlighting the selected source lines and labeling every source as demo data.
- [x] Feed the flowchart renderer through a typed normalized catalog; the standalone demo supplies its local fixture explicitly.
- [x] Feed modernization recommendations through a typed catalog supplied explicitly by the demo fixture; keep the React view independent of fixture contents.
- [x] Demonstrate Java 2 → Java 16, monolith decomposition, and optimization recommendations with a sample diff and visible pending states for build, tests, behavior, and metrics.
- [x] Demonstrate an editable BPMN 2.0 plan with typed demo operation extensions, XML export, undo/reset, and a preview that makes clear Java was not changed.
- [x] Make the standalone prototype available at `/demo` without requiring a project upload or graph API.
- [x] Make partial and failed parser results visually distinct from a complete analysis and direct attention to diagnostics.

### M1 — Connect the parser graph

- [x] Rust exposes the parser's current-revision `project_graph` through `GET /projects/{id}/graph` in a revisioned API response.
- [x] Frontend consumes that response through the typed API adapter and opens the real type graph; API errors never switch to fixture data.
- [x] Selecting a type or method opens the matching source range; method navigation resolves to the existing CFG and complexity endpoints.
- [x] Partial analysis, unsupported constructs, unresolved calls, and stale graph revisions are visible in the UI.

### M2 — Import and understand a whole repository

- [ ] Backend accepts a complete Java repository and resolves its project classpath, including Maven/Gradle dependencies.
- [ ] Backend exposes project-scoped hierarchy and nested behavior-flow data with file-qualified source ranges and explicit completeness/unsupported information.
- [ ] Frontend navigates project → flow → package/module → type → method → source, opening the correct file before selecting its range.
- [x] Project graph navigation supports global package/type search and package drill-down; the root shows collapsed type-level relationships, not callable-level edges, and caps visible types.

### M3 — Recommend and model a modernization

- [ ] Backend returns actionable findings and migration/decomposition proposals with rationale, affected files, and supported transformation IDs.
- [ ] Frontend renders proposals through an adapter and lets users inspect scope and edit only supported BPMN process elements.
- [ ] The selected BPMN model maps to explicit backend operations; unsupported or ambiguous edits are rejected visibly.
- [ ] Confirm production adoption of `bpmn-js` given its required visible watermark, or choose another BPMN 2.0 editor.

### M4 — Apply and verify changes

- [ ] Backend provides preview/diff, explicit apply, revision history, and rollback for supported operations.
- [ ] Java-version migration and scoped monolith decomposition work end to end for declared supported project shapes.
- [ ] Build and existing project checks run before a result is accepted; behavior comparison reports exactly which scenarios passed or failed.
- [ ] Before/after runtime results include reproducible workload and environment details.

## Current baseline

- The API accepts one `.java` file per project; whole-repository import and project-wide classpath analysis are not implemented.
- The parser emits per-method CFGs and a compilation-unit architectural/dependency graph, now exposed by the revision-bound `/graph` API; it does not produce the hierarchical business-flow diagrams in this roadmap. See `apps/parser/README.md` for graph identity and source-range constraints.
- The current Project Overview requests the API graph; `/demo` supplies a multi-file project graph fixture with package drill-down and source preview plus clearly labeled synthetic flowcharts and modernization catalogs. Demo data is not analysis of an uploaded source. Production project-scope file opening, real project-flow data, and project-wide transformation APIs are still required.
- Existing transformations are limited. The BPMN-like plan and Java-version migration are frontend prototypes only; there is no backend operation to apply either one.
