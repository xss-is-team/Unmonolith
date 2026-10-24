# Java Blueprints web

Next.js App Router frontend. Rust/Axum remains the only application API; Next
proxies `/api/v1/*` to it so the browser uses one origin and keeps the guest
session cookie HttpOnly.

## Run locally

After installing host tools, start the whole app from the repository root with:

```sh
./dev up
```

Open <http://localhost:3001>. By default Next proxies to Axum at
`http://127.0.0.1:3000`; set `BLUEPRINTS_API_ORIGIN` to change the target.
The command installs frontend packages, builds the parser and API, and starts
both services. Press Ctrl+C to stop them.

The current UI supports one `.java` file per project, source viewing and
revision-checked saving. With the parser built, analysis shows returned methods
and diagnostics. The CFG viewer loads the documented per-method route and maps
selected real nodes to source ranges. If the backend route is unavailable, the
viewer reports that state; a separate, explicitly labeled fixture is available
for UI development and is never presented as analysis output. Condition edits
remain disabled until the transform endpoint is implemented.

Run frontend lint and production build with `./dev web-check`.
