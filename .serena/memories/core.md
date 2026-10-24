# Project map

- Product goals and stages: `ROADMAP.md`.
- Target: whole-project Java modernization with nested read-only flowcharts, separate editable business-process diagrams mapped to code transformations, and verified before/after results.
- Current web prototype: `/demo` has synthetic project flowcharts, a multi-file package/type graph with global search and drill-down, and an editable BPMN 2.0 plan with `bp:Operation` extensions and XML export. Demo fixtures are not analysis and never modify uploaded source. The live graph API remains single-file; project-scope source-file navigation is pending.
- Backend currently exposes the parser's compilation-unit `project_graph` at `GET /projects/{id}/graph`; upload still accepts one `.java` file. Whole-repository import/classpath, business-flow endpoint, Java migration, and project decomposition are not implemented. It supports a limited `replace_condition` transform and scenario verification.
- Stack and fixture: `mem:tech_stack` and `README.md`. Keep `legacy/commons-math-3.6.1/` unchanged.
