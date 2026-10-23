# Java Blueprints — end-to-end architecture

## Data flow

```text
.java → Java parser → AST / source map → CFG + editable model
      → diagnostics / refactoring → Java generator → compile + tests
                                                   → behavior comparison
```

Keep one canonical editable model. The editor changes that model; the generator emits Java from it. Preserve source locations so graph nodes, diagnostics, and generated changes can be traced to code. Reparse generated Java before validation to catch generator/model mismatches.

## Components and terms

- **Parser** — converts supported Java source into an **AST** (abstract syntax tree: nested program structure). Use an existing Java parser; the hackathon permits libraries.
- **CFG** — control-flow graph: basic blocks connected by possible execution paths; branches represent conditions, back edges represent loops. It is a view/analysis structure, not a replacement for the full AST.
- **Model + source map** — editable representation and links between model nodes and source ranges. Defines round-trip synchronization between code and graph.
- **Analyzer** — attaches diagnostics to nodes and source locations; reports complexity with rationale (brief example: nested scan, `O(n²)`, repeated matching by `id`).
- **Refactoring** — explicit model transformation with a preview/diff; keep original source available for rollback.
- **Generator** — serializes the changed model to Java within the supported language scope.
- **Quality gate** — compile, run automated tests, and compare observable behavior for the supplied scenarios. Passing tests is evidence for those scenarios, not proof for every possible input.

## End-to-end acceptance

Load Java → build graph → edit → generate Java → compile → run tests → compare behavior. Report unsupported syntax and validation failures; do not silently drop constructs.

Implement the brief's mandatory Java scope first: core language/`java.lang`, console I/O, `java.util` collections, and exceptions. The brief permits parsers, graph/UI libraries, frameworks, and AI. It does not prescribe a parser, storage design, or a specific AST-to-CFG strategy.

## Legacy test corpus

`legacy/commons-math-3.6.1/` is the pinned Apache Commons Math 3.6.1 codebase used as a large Java parser and analysis test corpus. Keep the fixture unchanged; see `legacy/commons-math-3.6.1/README-test-fixture.md` for provenance and use.
