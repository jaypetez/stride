import { defineConfig } from 'vitest/config';
import { coverageScope } from '../../scripts/vitest-coverage-scope.ts';

// Coverage policy: docs/testing/coverage.md. Thresholds are the measured
// coverage rounded DOWN and only ever go up: `pnpm coverage:ratchet` rewrites
// them (floored) when coverage improves. `pnpm coverage` fails below them.
//
// `coverageScope()` narrows the thresholds on a filename-filtered run, so
// `vitest run planner --coverage` checks only the `src/coach/planner.ts` glob
// (AUD-014) instead of the package-wide numbers, which a partial run can't meet.
export default defineConfig({
  plugins: [coverageScope()],
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
        'src/coach/planner.ts': {
          lines: 99,
          statements: 99,
          functions: 100,
          branches: 96,
        },
      },
    },
  },
});
