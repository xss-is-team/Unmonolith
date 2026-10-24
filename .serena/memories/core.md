# Project map

- Product goals and stages: `ROADMAP.md`.
- Target: whole-project Java modernization with nested read-only flowcharts, separate editable business-process diagrams mapped to code transformations, and verified before/after results.
- Current web prototype: `/demo` opens labeled synthetic flowcharts from a typed catalog, with hierarchy search and links to source-range excerpts in `LegacyOrderApp.java`, plus a migration/decomposition/BPMN-plan demo with local undo/reset; it does not analyze or modify uploaded source.
- Backend limits: one `.java` file per project; no repository import/classpath, project-flow endpoint, Java migration, or project decomposition yet. It does support a limited `replace_condition` transform and scenario verification.
- Stack and fixture: `mem:tech_stack` and `README.md`. Keep `legacy/commons-math-3.6.1/` unchanged.
