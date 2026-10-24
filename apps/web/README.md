# Java Blueprints web

Next.js App Router frontend. Rust/Axum remains the only application API; Next
proxies `/api/v1/*` to it so the browser uses one origin and keeps the guest
session cookie HttpOnly.

## Run locally

From the repository root, install dependencies and build the parser once:

```sh
./dev web-install
./dev parser-build
```

Then start the API and web app in separate terminals:

```sh
BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21 ./dev api
./dev web
```

Or start the whole app from the repository root with:

```sh
./dev up
```

Open <http://localhost:3001>. By default Next proxies to Axum at
`http://127.0.0.1:3000`; set `BLUEPRINTS_API_ORIGIN` to change the target.
The command installs frontend packages, builds the parser and API, and starts
both services. Press Ctrl+C to stop them.

The current API accepts one `.java` file per project. The UI provides source
viewing and revision-checked autosaving. With the parser built, analysis starts
automatically and shows source-linked diagnostics, per-method CFG, Big O findings,
and `replace_condition` through ASTRewrite. Revision text diff and isolated
behavioral comparison are separate views. Verification requires `./dev
runner-build` and `BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21`;
without it the API returns `runner_unavailable`.

The parser emits a compilation-unit `project_graph`, but the API graph endpoint
is not available yet. Project Overview requests that endpoint; in development,
the type graph fixture can be opened explicitly and is labeled as test data.
Fixture nodes never navigate to the uploaded project's source or CFG. The demo
flow and modernization catalogs use typed frontend models supplied explicitly
by local fixtures; they do not analyze or modify the uploaded source.

Open `/demo` to explore those frontend prototypes without uploading a source
file or calling the project-graph API. The editable process plan is a BPMN 2.0
XML model with local operation extensions; it exports `.bpmn` and never applies
changes to Java. The bpmn.io watermark remains visible as required by its license.

This prototype shows type relationships for one source file. The product target
is a whole-project hierarchy of read-only flowcharts plus a separate editable
BPMN-style process diagram that proposes verified code transformations; neither is provided
by the current API.

Run frontend typecheck, lint and production build with `./dev web-check`.
