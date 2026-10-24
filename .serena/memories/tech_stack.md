# Stack and architecture

- Runtime layout: `apps/api/` is the Rust/Axum API, `apps/parser/` is a Maven/Eclipse JDT CLI that emits AST JSON, and `apps/web/` is the Next.js App Router UI. Root `flake.nix` is optional; host setup is documented in `README.md`.
- API invokes the parser JAR with a source path, validates its JSON AST result, attaches revision metadata, and stores the full artifact. The UI currently displays method summaries and diagnostics; CFG and complexity analysis are not implemented.
- Intended flow: Java parser → AST/source map → CFG + canonical editable model → analysis/refactoring → Java generator → reparse, compile, tests, behavior comparison.
- Required initial language coverage per brief: core Java/`java.lang`, console I/O, `java.util` collections, and exceptions. Graph visualization/model design remains undecided.
- Keep source locations linked through model nodes, diagnostics, and generated edits. Do not silently discard unsupported syntax; retain original source for rollback.