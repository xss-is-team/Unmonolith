# Stack and architecture

- `apps/api/`: Rust/Axum API for projects, analysis, revisions, isolated verification, and the revision-bound parser graph endpoint `GET /projects/{id}/graph`. Current upload accepts one `.java` file.
- `apps/parser/`: Java/Maven with Eclipse JDT. Emits AST, diagnostics, per-method CFG, complexity findings, and a compilation-unit project graph; supports limited `replace_condition` transformation.
- `apps/web/`: Next.js App Router. Current UI includes source editor, diagnostics, CFG/Big O, revisions, verification, a type-graph prototype, synthetic nested flows, and a `bpmn-js` BPMN 2.0 plan editor demo with typed `bp:Operation` XML extensions. Flow and modernization views consume typed fixture catalogs.
- Project-graph API payloads are runtime-validated, including node references and source ranges. Match graph callables to CFG methods by `method_id` plus exact `source_range`; `method_id` can collide for local classes.
- The API lacks whole-repository import/classpath, project business-flow, and project-wide modernization endpoints. It supports only a limited method-condition transform plus revisioned scenario verification. Real project flows, Java-version migration, monolith decomposition, and before/after runtime measurements remain future work; see `ROADMAP.md`.
- Synthetic demo data must never appear as analysis of an uploaded project. Preserve `legacy/commons-math-3.6.1/` unchanged.
