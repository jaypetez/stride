import { defineConfig } from 'vitest/config';

// Root-level tests for repo scripts (packages run their own suites via Turbo).
//
// Coverage policy: docs/testing/coverage.md. Thresholds are the measured
// coverage rounded DOWN and only ever go up: `pnpm coverage:ratchet` rewrites
// them (floored) when coverage improves. `pnpm coverage` fails below them.
export default defineConfig({
  test: {
    include: ['scripts/**/*.test.ts'],
    testTimeout: 60_000,
    coverage: {
      provider: 'v8',
      include: ['scripts/**/*.{ts,mts}'],
      exclude: ['scripts/**/*.test.ts'],
      reportsDirectory: 'coverage/scripts',
      reporter: ['text', 'json-summary', 'lcov'],
      thresholds: {
        autoUpdate: process.env.COVERAGE_RATCHET ? (n: number) => Math.floor(n) : false,
        lines: 94,
        statements: 95,
        functions: 98,
        branches: 92,
      },
    },
  },
});
