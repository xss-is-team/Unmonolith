# Java Blueprints — end-to-end architecture

> **Что это.** Веб-инструмент для кейса Axenix «Java Blueprints»: Java-монолит → AST/CFG/граф проекта → диагностика Big O →
> правка и оптимизация → регенерация Java → проверка сохранения поведения в Docker. Описание решения: [`docs/SOLUTION.md`](docs/SOLUTION.md),
> соответствие кейсу: [`docs/CASE_COMPLIANCE.md`](docs/CASE_COMPLIANCE.md), сохранённый реальный прогон: [`docs/evidence/demo/SUMMARY.md`](docs/evidence/demo/SUMMARY.md)
> (повтор: `./dev demo`). Бэкенд готов; интерфейс догоняет API (см. список расхождений в compliance).

## Current application

The web client lives in `apps/web/` and uses Next.js App Router. It provides source-linked diagnostics, per-method CFG and Big O views, a Project Overview UI, revision diff, `replace_condition`, and behavioral verification. Project Overview reads the revisioned graph endpoint for the current single-file project; its explicitly labeled multi-file fixture is only for `/demo`. The Rust/Axum service in `apps/api/` remains the API and owns guest sessions, project storage, revisions, analysis and runner reports. Next proxies `/api/v1/*` to Axum so browser requests and the session cookie stay same-origin.

The three application folders share one Git repository: `apps/api/` (Rust), `apps/parser/` (Java/Maven), and `apps/web/` (Next.js). Install the tools with your OS package manager, or use the optional root `flake.nix` with `nix develop`.

Required tools: stable Rust, JDK 21, Maven, Node.js 22 or newer, pnpm 11, Git, and pkg-config. Docker is required only for isolated Java execution and behavioral verification.

Arch Linux:

```sh
sudo pacman -S --needed rustup jdk21-openjdk maven nodejs pnpm git pkgconf docker
rustup default stable
```

macOS with Homebrew:

```sh
brew install rustup openjdk@21 maven node@22 pnpm@11 pkgconf git docker
export PATH="$(brew --prefix rustup)/bin:$PATH"
rustup default stable
```

Nix (optional): from the repository root, run `nix develop`.

## Common commands

The root `./dev` wrapper keeps routine commands short. Install web dependencies and build the parser once:

```sh
./dev web-install
./dev parser-build
./dev runner-build
```

Then start API and web in separate terminals (verification is enabled only when the runner image is configured):

```sh
BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21 ./dev api
./dev web
```

Open <http://localhost:3001>. The API listens on port 3000; the web app proxies its requests to it. Set `BLUEPRINTS_API_ORIGIN` if Axum listens elsewhere. Set `BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21` when starting the API to enable verification. `./dev` lists the parser and runner commands. Frontend details are in `apps/web/README.md`.

For a quick frontend check during UI work, run `./dev web-fast-check` (TypeScript and lint). Run `./dev web-check` before handing work off; it also makes a production build.
## Run after cloning

After installing the host tools listed above, run one command from the repository root:

```sh
./dev up
```

It installs frontend packages, builds the parser and API, then starts both services. Open <http://localhost:3001>; the API is at <http://localhost:3000>. Press Ctrl+C to stop both. To include behavioral verification, first run `./dev runner-build`, then start with `BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21 ./dev up`. `./dev` lists individual build and check commands. Frontend details are in `apps/web/README.md`.

Or run everything in containers (needs only Docker, builds the parser, API, web and the runner image):

```sh
docker compose up --build
```

Open <http://localhost:3001>. The API container uses the host Docker socket to start throw-away runner containers; run it on a dedicated machine (see `compose.yaml`).

## Parser integration

`./dev parser-build` creates an executable JAR. `./dev api` points the API at that JAR automatically; `BLUEPRINTS_ANALYZER_JAR` can override it. The API invokes the parser for each analysis request and stores its JSON result with the project revision. If the JAR has not been built, analysis returns `analyzer_unavailable`. The parser provides AST data, semantic diagnostics, per-method CFGs, and the restricted `replace_condition` rewrite.

`./dev runner-build` creates the Java 21 image used by the separate `/verify` operation. The runner is opt-in and never falls back to host execution; see `apps/api/README.md` for configuration and security limitations.

## Product direction

See [ROADMAP.md](ROADMAP.md) for global goals, frontend work that can start now, and backend-dependent milestones.

The target is to open a whole Java project and immediately show its main behavior flows using familiar flowchart symbols. The engineer drills down from project to flow, module/package, type, and method. These flowcharts are for navigation and understanding; architecture/dependency maps are a separate view, not a substitute for behavior flows.

Changes happen through a separate editable BPMN-style process diagram. Its supported elements map to explicit Java transformations; changing a diagram proposes a code change, it does not silently rewrite source. The product applies a chosen action, such as migrating a legacy monolith from the Java 2 era to a target like Java 16, decomposing part of it into services, or optimizing a detected hot spot. The actual source level must come from project configuration or be confirmed when it is unknown. It should show the source diff, compile and run the project's checks, compare behavior, and report before/after metrics such as runtime. Users edit source manually only when the product cannot safely handle a case; then it should take them to the specific code and explain what needs attention. Test results support the tested scenarios; they cannot prove identical behavior for every possible input.

## Current scope

- The API accepts a single `.java` file or a ZIP project (multi-file, packages, source levels Java 8–21); Maven/Gradle import and external dependencies are not supported (unresolved calls are reported honestly).
- The parser produces AST data, per-method CFGs, Big O estimates with findings, a project graph, `replace_condition` and `move_statement` edits, three optimization rules (nested lookup → `HashMap`, `list.contains` in a loop → `HashSet`, `String +=` in a loop → `StringBuilder`) and the decomposition of a monolith (static member types → files, groups of static methods → classes). The Rust API exposes these through the revisioned endpoints in `apps/api/openapi.yaml`, plus rollback, export, a Markdown report and a runtime benchmark.
- The web app covers source editing, per-method CFG and Big O, the project overview and Big O summary, `replace_condition`, `move_statement`, optimization preview/apply with assumptions, behavioural verification (repeats, expectations, warnings), the runtime benchmark, monolith decomposition, revision diff, ZIP upload, project export and the Markdown report. Not in the UI: rollback screen, block drag-and-drop, debugger.
- The `/demo` route and its synthetic fixtures described in earlier versions are not present in `apps/web`; every view reads real API data.
- See `docs/SOLUTION.md` for architecture, supported Java scope and limitations.

## Core concepts

- **AST** — nested structure of declarations and statements, with source ranges.
- **CFG** — possible execution paths inside one method; conditions branch and loops return to earlier blocks.
- **Project graph** — types and relationships across the analyzed scope. It complements the CFG; it is not a flowchart of every statement.
- **Quality gate** — compile and run automated checks, then compare observable behavior for tested scenarios. Passing tests is evidence for those scenarios, not proof for every possible input.

## Legacy test corpus

`legacy/commons-math-3.6.1/` is the pinned Apache Commons Math 3.6.1 codebase used as a large Java parser and analysis test corpus. Keep the fixture unchanged; see `legacy/commons-math-3.6.1/README-test-fixture.md` for provenance and use.
