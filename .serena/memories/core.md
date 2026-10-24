# Project map

- Java Blueprints: Java AST and statement-level CFG analysis, CFG viewing, source-linked diagnostics, revisions, and browser editing.
- Current implementation: `README.md`; roadmap status: `ROADMAP.md`; future project-import and refactoring criteria: `PROJECT_REQUIREMENTS.md`.
- Completed A, B, E, and UX polish. C auto-start and progressive results are implemented; incremental recalculation remains open. See `ROADMAP.md`.
- Long-term target: import full Maven/Gradle projects with dependency-aware file analysis; let architects decompose/refactor through project graphs; regenerate, rebuild, and test the project. Validate this on an unchanged `legacy/commons-math-3.6.1/` corpus copy. Acceptance criteria: `PROJECT_REQUIREMENTS.md`.
- Product CFG views must use parser/API results; do not add demo graphs as analysis output.