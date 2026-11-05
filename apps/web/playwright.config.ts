import os from 'node:os';
import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the REAL stack: the Hono API on :8720 (demo mode is
 * fully offline) and the Vite dev server on :5173, which proxies `/api` to it.
 * Without the API every card would just say "API unavailable", so both servers
 * start here and the specs assert real demo data renders (AUD-019).
 *
 * Browsers are not installed by default; run
 * `pnpm --filter @stride/web exec playwright install chromium` first, then
 * `pnpm --filter @stride/web e2e`.
 */
const isCI = !!process.env.CI;
const API_PORT = 8720;
const WEB_PORT = 5173;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  reporter: isCI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      // No `watch`: e2e wants one stable process, not the dev reloader.
      command: 'pnpm --filter @stride/api exec tsx src/index.ts',
      url: `http://localhost:${API_PORT}/health`,
      reuseExistingServer: !isCI,
      timeout: 60_000,
      env: {
        STRIDE_API_PORT: String(API_PORT),
        // Pinned clock + an empty throwaway store: deterministic demo output and
        // a genuinely empty "My data" view.
        STRIDE_NOW: '2026-07-09T00:00:00Z',
        STRIDE_DATA_DIR: path.join(os.tmpdir(), 'stride-web-e2e'),
        // Never call a live LLM from e2e, even if the machine has a key set
        // (dotenv does not override a variable that is already defined).
        ANTHROPIC_API_KEY: '',
      },
    },
    {
      command: `pnpm exec vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      reuseExistingServer: !isCI,
      timeout: 60_000,
    },
  ],
});
