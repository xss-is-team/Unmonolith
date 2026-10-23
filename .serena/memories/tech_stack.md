# Stack and architecture

- Repository currently documents a Java source-to-visual-model tool; root contains planning material, with no app/build manifest identified during onboarding. Serena has Rust and Java language servers active, which does not by itself establish project dependencies.
- Intended flow: Java parser → AST/source map → CFG + canonical editable model → analysis/refactoring → Java generator → reparse, compile, tests, behavior comparison.
- Required initial language coverage per brief: core Java/`java.lang`, console I/O, `java.util` collections, exceptions. Parser/UI/graph libraries are open choices.
- Keep source locations linked through model nodes, diagnostics, and generated edits. Do not silently discard unsupported syntax; retain original source for rollback.