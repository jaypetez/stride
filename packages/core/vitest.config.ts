import { defineConfig } from 'vitest/config';

// Coverage policy: docs/testing/coverage.md. Thresholds are the measured
// coverage rounded DOWN and only ever go up: `pnpm coverage:ratchet` rewrites
// them (floored) when coverage improves. `pnpm coverage` fails below them.
export default defineConfig({
  test: {
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      reporter: ['text', 'json-summary', 'lcov'],
      thresholds: {
        autoUpdate: process.env.COVERAGE_RATCHET ? (n: number) => Math.floor(n) : false,
        lines: 92,
        statements: 89,
        functions: 88,
        branches: 79,
      },
    },
  },
});
