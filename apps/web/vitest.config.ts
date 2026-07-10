import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Unit/component tests live under src/ and run in a browser-free happy-dom
// environment. The Playwright e2e suite (e2e/) is run separately via
// `pnpm --filter @stride/web e2e`, so keep Vitest out of it.
export default defineConfig({
  plugins: [react()],
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    passWithNoTests: true,
  },
});
