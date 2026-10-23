# Project conventions

- Maintain one canonical editable model; the graph is a view/analysis structure, not a substitute for the full AST.
- Preserve source ranges and traceability between source, model nodes, diagnostics, and generated code.
- Represent edits as explicit model transformations with preview/diff; retain original source for rollback.
- Reparse generated Java before validation to catch model/generator mismatches.
- Explain AST/CFG concepts concretely for an enthusiastic practitioner; avoid unnecessary compiler theory jargon (per project brief).