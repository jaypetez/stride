import { readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

/**
 * Scopes Vitest coverage thresholds to a filename-filtered run.
 *
 * `vitest run planner --coverage` is still treated as an "all tests run" by
 * @vitest/coverage-v8, and with `coverage.include: ['src/**']` every source
 * file the filtered tests never load counts at 0%. A package-wide threshold
 * (core: 92% lines) can then never pass on a filtered run.
 *
 * When the command line carries test-file filters, this plugin:
 * - drops the package-wide (global) thresholds,
 * - keeps only the glob thresholds whose glob mentions one of the filters
 *   (`planner` keeps `src/coach/planner.ts`), and
 * - turns off `autoUpdate`, so a partial run never ratchets the config.
 *
 * An unfiltered run (`vitest run --coverage`, `pnpm coverage`) is untouched,
 * so the full gate and `pnpm coverage:ratchet` behave exactly as before. The
 * thresholds stay literal in each `vitest.config.*` so `autoUpdate` can still
 * rewrite them. Policy: docs/testing/coverage.md.
 */

const GLOBAL_KEYS = ['lines', 'statements', 'functions', 'branches', 'perFile', '100'] as const;
const SUBCOMMANDS = new Set(['run', 'watch', 'dev', 'related', 'bench', 'list', 'init']);
const TEST_FILE = /\.(test|spec)\.[cm]?[jt]sx?$/;

const normalize = (p: string) => p.replace(/\\/g, '/');

/** Test files under `root` (relative, forward slashes), skipping node_modules and dist. */
export function listTestFiles(root: string): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === 'node_modules' || entry.name === 'dist' || entry.name.startsWith('.')) {
        continue;
      }
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (TEST_FILE.test(entry.name)) out.push(normalize(relative(root, full)));
    }
  };
  walk(root);
  return out.sort();
}

/**
 * The positional test-file filters on a Vitest command line. A token counts
 * only if it is not a flag or subcommand and matches (as a substring, the way
 * Vitest filters) at least one test file, so a flag value such as
 * `--reporter verbose` is never mistaken for a filter.
 */
export function testFilters(argv: readonly string[], testFiles: readonly string[]): string[] {
  const binIndex = argv.findIndex((a) => /vitest(\.m?js)?$/.test(normalize(a)));
  const args = argv.slice(binIndex >= 0 ? binIndex + 1 : 2);
  return args.filter(
    (a) =>
      a !== '' &&
      !a.startsWith('-') &&
      !SUBCOMMANDS.has(a) &&
      testFiles.some((f) => f.includes(normalize(a))),
  );
}

export type Thresholds = Record<string, unknown>;

/**
 * Narrow `thresholds` in place for a run filtered by `filters`. No filters:
 * left unchanged. Returns the same object for convenience.
 */
export function scopeThresholds(thresholds: Thresholds, filters: readonly string[]): Thresholds {
  if (filters.length === 0) return thresholds;
  for (const key of GLOBAL_KEYS) delete thresholds[key];
  thresholds.autoUpdate = false;
  for (const key of Object.keys(thresholds)) {
    if (key === 'autoUpdate') continue;
    if (!filters.some((f) => key.includes(normalize(f)))) delete thresholds[key];
  }
  return thresholds;
}

interface ConfigureVitestContext {
  vitest: { config: { root: string; coverage: { thresholds?: Thresholds } } };
}

/** Vitest plugin: apply {@link scopeThresholds} for the current command line. */
export function coverageScope(argv: readonly string[] = process.argv) {
  return {
    name: 'stride:coverage-scope',
    configureVitest({ vitest }: ConfigureVitestContext) {
      const thresholds = vitest.config.coverage.thresholds;
      if (!thresholds) return;
      const filters = testFilters(argv, listTestFiles(vitest.config.root));
      scopeThresholds(thresholds, filters);
    },
  };
}
