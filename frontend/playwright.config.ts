import { defineConfig, devices } from "@playwright/test";

/**
 * E2E tests against a running stack (AUFGABE 10.1 item 9): in CI the complete compose stack behind the proxy
 * (E2E_BASE_URL=https://localhost), locally `pnpm dev` with the API on :8080. The setup token comes from the API
 * log (E2E_SETUP_TOKEN); without it the setup must already be completed. See docs/entwicklung.md.
 */
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    ignoreHTTPSErrors: true,
    locale: "de-DE",
    timezoneId: "Europe/Berlin",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /setup\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: "e2e/.auth/admin.json" },
      dependencies: ["setup"],
    },
  ],
});
