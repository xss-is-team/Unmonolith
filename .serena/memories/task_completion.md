# Completion checks

- Expected end-to-end quality gate: load Java → build graph/model → edit → generate Java → reparse → compile → run automated tests → compare observable behavior for supplied scenarios.
- No executable project-specific commands are documented yet; identify the chosen build/test tooling before running this gate.
- Passing scenario tests is evidence only for those scenarios, not proof of equivalence for all inputs.