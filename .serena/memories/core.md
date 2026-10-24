# Project map

- Java Blueprints: Java AST and statement-level CFG analysis, CFG viewing, source-linked diagnostics, revisions, and browser editing.
- Current implementation: `README.md`; roadmap status: `ROADMAP.md`; future project-import and refactoring criteria: `PROJECT_REQUIREMENTS.md`.
- Completed E: per-method CCN, cognitive complexity, nesting depth and source hotspots pass parser → API → UI; tested against Commons Math and in Chromium.
- Long-term target: import full Maven/Gradle projects with dependency-aware file analysis; let architects decompose/refactor through project graphs; regenerate, rebuild, and test the project. Validate this on an unchanged `legacy/commons-math-3.6.1/` corpus copy. Acceptance criteria: `PROJECT_REQUIREMENTS.md`.
- Product CFG views must use parser/API results; do not add demo graphs as analysis output.