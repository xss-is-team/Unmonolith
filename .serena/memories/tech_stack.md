# Stack and architecture

- `apps/parser/`: Maven CLI built on Eclipse JDT; emits AST, semantic diagnostics, and statement-level CFG.
- `apps/api/`: Rust/Axum guest-session API; invokes the Java parser process, validates/stores revision-scoped results, streams analysis progress, serves method CFG, OpenAPI, and Swagger UI. Persistence is local filesystem.
- `apps/web/`: Next.js App Router, CodeMirror Java editor, React Flow CFG viewer with ELK layout and Dagre fallback. Next proxies API and Swagger routes on the same origin.
- Product CFG uses parser/API output only. Unit tests retain isolated process doubles to test malformed, failed, oversized, and timed-out worker responses. No external SaaS integrations.
- Implemented: AST/CFG analysis, diagnostics, revisions, CFG/source navigation, autosave, SSE progress, and revision diffs. Not implemented: whole-project import/classpath resolution, complexity analysis, graph transformations, Java generation, project rebuild, or behavior-equivalence runner. See `README.md`, `ROADMAP.md`, and `PROJECT_REQUIREMENTS.md`.