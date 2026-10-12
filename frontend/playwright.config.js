import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  workers: 1,
  retries: 0,
  outputDir: "../.cache/playwright-results",
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:5173",
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 30000,
    navigationTimeout: 30000,
    trace: "off",
    screenshot: "off",
  },
  reporter: "list",
});
