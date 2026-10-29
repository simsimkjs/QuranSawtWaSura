const { defineConfig } = require("@playwright/test");
const path = require("path");

const PORT = 8130;
const python = process.env.CI ? "python3" : "python";

module.exports = defineConfig({
  testDir: "./tests",
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? [["github"], ["list"]] : [["list"]],
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    // نستخدم Edge المثبّت محليًا بدل تنزيل Chromium؛ وفي CI نستخدم Chromium
    channel: process.env.CI ? undefined : "msedge",
    headless: true,
    viewport: { width: 1280, height: 850 },
    actionTimeout: 10_000,
    trace: process.env.CI ? "retain-on-failure" : "off",
  },
  webServer: {
    command: `${python} web/serve.py -p ${PORT} -d .`,
    cwd: path.resolve(__dirname, ".."),
    url: `http://127.0.0.1:${PORT}/web/`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
