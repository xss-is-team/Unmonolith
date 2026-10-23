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
./dev api
./dev web
```

Open <http://localhost:3001>. By default Next proxies to Axum at
`http://127.0.0.1:3000`; set `BLUEPRINTS_API_ORIGIN` to change the target.

The current UI supports one `.java` file per project, source viewing and
revision-checked saving. With the parser built, analysis shows returned methods
and diagnostics. CFG visualization is not implemented yet; without a parser
JAR, the API returns `analyzer_unavailable`.

Run frontend lint and production build with `./dev web-check`.
