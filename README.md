# Java Blueprints — end-to-end architecture

## Current application

The web client lives in `apps/web/` and uses Next.js App Router. It provides a Java source upload and editing workspace; the Rust/Axum service in `apps/api/` remains the API and owns guest sessions, project storage, revisions, and analysis. Next proxies `/api/v1/*` to Axum so browser requests and the session cookie stay same-origin.

The three application folders share one Git repository: `apps/api/` (Rust), `apps/parser/` (Java/Maven), and `apps/web/` (Next.js). Install the tools with your OS package manager, or use the optional root `flake.nix` with `nix develop`.

Required tools: stable Rust, JDK 21 or newer, Maven, Node.js 22 or newer, pnpm 11, Git, and pkg-config.

Arch Linux:

```sh
sudo pacman -S --needed rustup jdk-openjdk maven nodejs pnpm git pkgconf
rustup default stable
```

macOS with Homebrew:

```sh
brew install rustup openjdk@25 maven node@22 pnpm@11 pkgconf git
export PATH="$(brew --prefix rustup)/bin:$PATH"
rustup default stable
```

Nix (optional): from the repository root, run `nix develop`.

## Run after cloning

After installing the host tools listed above, run one command from the repository root:

```sh
./dev up
```

It installs frontend packages, builds the parser and API, then starts both services. Open <http://localhost:3001>; the API is at <http://localhost:3000>. Press Ctrl+C to stop both. `./dev` lists individual build and check commands. Frontend details are in `apps/web/README.md`.

## Parser integration

`./dev parser-build` creates an executable JAR. `./dev api` points the API at that JAR automatically; `BLUEPRINTS_ANALYZER_JAR` can override it. The API invokes the parser for each analysis request and stores its JSON result with the project revision. If the JAR has not been built, analysis returns `analyzer_unavailable`. The parser provides an AST, semantic diagnostics, and statement-level CFG. Complexity analysis, transformations, Java generation, compilation, and behavior verification are not implemented yet.

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
