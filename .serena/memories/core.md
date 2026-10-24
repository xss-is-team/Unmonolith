# Project map

- Product goals and stages: `ROADMAP.md`.
- Target: whole-project Java modernization with nested read-only flowcharts, separate editable business-process diagrams mapped to code transformations, and verified before/after results.
- Current web prototype: `/demo` has synthetic project flowcharts, a multi-file package/type graph with global search and drill-down, fixture-only source previews with selected lines, and an editable BPMN 2.0 plan with typed demo operations. Fixtures do not analyze or modify uploaded source. The live graph API remains single-file; production project-scope source navigation is pending.
- Backend currently exposes the parser's compilation-unit `project_graph` at `GET /projects/{id}/graph`; upload still accepts one `.java` file. Whole-repository import/classpath, business-flow endpoint, Java migration, and project decomposition are not implemented. It supports a limited `replace_condition` transform and scenario verification.
- Stack and fixture: `mem:tech_stack` and `README.md`. Keep `legacy/commons-math-3.6.1/` unchanged.
