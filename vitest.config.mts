import { defineConfig } from 'vitest/config';

// Root-level tests for repo scripts (packages run their own suites via Turbo).
export default defineConfig({
  test: {
    include: ['scripts/**/*.test.ts'],
    testTimeout: 60_000,
  },
});
