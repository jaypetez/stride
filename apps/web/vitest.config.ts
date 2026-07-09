import { defineConfig } from 'vitest/config';

// Unit tests live under src/. The Playwright e2e suite (e2e/) is run separately
// via `pnpm --filter @stride/web e2e`, so keep Vitest out of it.
export default defineConfig({
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    passWithNoTests: true,
  },
});
