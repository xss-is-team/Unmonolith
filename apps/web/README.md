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

The current UI supports one `.java` file per project, source viewing and
revision-checked autosaving. With the parser built, analysis starts
automatically and shows source-linked diagnostics, actual nested per-method CFG,
Big O findings, and `replace_condition` through ASTRewrite. Revision text diff
and isolated behavioral comparison are separate views. Verification requires
`./dev runner-build` and `BLUEPRINTS_RUNNER_IMAGE=java-blueprints-runner:java21`;
without it the API truthfully returns `runner_unavailable`.

Run frontend typecheck, lint and production build with `./dev web-check`.
