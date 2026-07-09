import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm'],
  target: 'node22',
  clean: true,
  // Bundle the workspace packages (they ship as TS source, not built JS).
  noExternal: [/^@stride\//],
});
