import { defineConfig } from '@playwright/test';

/**
 * Opt-in end-to-end smoke test. Browsers are not installed by default; run
 * `pnpm --filter @stride/web exec playwright install chromium` first, then
 * `pnpm --filter @stride/web e2e`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: { baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
