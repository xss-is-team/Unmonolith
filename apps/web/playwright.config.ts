import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const webPort = 3101;
const apiPort = 3100;
const root = process.cwd();
const runnerImage = process.env.BLUEPRINTS_REAL_RUNNER_IMAGE ?? "java-blueprints-runner:java21";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  outputDir: ".next-playwright/test-results",
  use: {
    baseURL: `http://127.0.0.1:${webPort}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ...devices["Desktop Chrome"],
  },
  webServer: [
    {
      command: "cargo run",
      cwd: path.resolve(root, "../api"),
      url: `http://127.0.0.1:${apiPort}/api/v1/healthz`,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
      env: {
        BLUEPRINTS_BIND: `127.0.0.1:${apiPort}`,
        BLUEPRINTS_DATA_DIR: path.resolve(root, ".next-playwright/api-data"),
        BLUEPRINTS_ANALYZER_JAR: path.resolve(root, "../parser/target/ASTPARSER-1.0-SNAPSHOT.jar"),
        BLUEPRINTS_JAVA_BIN: process.env.BLUEPRINTS_REAL_JAVA ?? "java",
        BLUEPRINTS_RUNNER_IMAGE: runnerImage,
      },
    },
    {
      command: `BLUEPRINTS_API_ORIGIN=http://127.0.0.1:${apiPort} pnpm exec next dev --port ${webPort}`,
      cwd: root,
      url: `http://127.0.0.1:${webPort}`,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
