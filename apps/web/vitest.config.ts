import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Unit/component tests live under src/ and run in a browser-free happy-dom
// environment. The Playwright e2e suite (e2e/) is run separately via
// `pnpm --filter @stride/web e2e`, so keep Vitest out of it.
//
// Coverage policy: docs/testing/coverage.md. Thresholds are the measured
// coverage rounded DOWN and only ever go up: `pnpm coverage:ratchet` rewrites
// them (floored) when coverage improves. `pnpm coverage` fails below them.
export default defineConfig({
  plugins: [react()],
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/test-setup.ts', 'src/**/*.d.ts'],
      reporter: ['text', 'json-summary', 'lcov'],
      thresholds: {
        autoUpdate: process.env.COVERAGE_RATCHET ? (n: number) => Math.floor(n) : false,
        lines: 64,
        statements: 62,
        functions: 52,
        branches: 64,
      },
    },
  },
});
