import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  coverageScope,
  listTestFiles,
  scopeThresholds,
  type Thresholds,
  testFilters,
} from './vitest-coverage-scope.ts';

const TEST_FILES = ['test/planner.test.ts', 'test/helpers.test.ts', 'test/sync.test.ts'];
const BIN = '/repo/node_modules/vitest/vitest.mjs';

const coreThresholds = (): Thresholds => ({
  autoUpdate: (n: number) => Math.floor(n),
  lines: 92,
  statements: 89,
  functions: 88,
  branches: 79,
  'src/coach/planner.ts': { lines: 99, statements: 99, functions: 100, branches: 96 },
  'src/science/**': { lines: 95 },
});

describe('testFilters', () => {
  it('returns no filters for an unfiltered run', () => {
    expect(testFilters(['node', BIN, 'run', '--coverage'], TEST_FILES)).toEqual([]);
    expect(testFilters(['node', BIN], TEST_FILES)).toEqual([]);
  });

  it('picks up positional filters that match a test file', () => {
    expect(testFilters(['node', BIN, 'run', 'planner', '--coverage'], TEST_FILES)).toEqual([
      'planner',
    ]);
    expect(testFilters(['node', BIN, 'run', '--coverage', 'planner', 'sync'], TEST_FILES)).toEqual([
      'planner',
      'sync',
    ]);
  });

  it('ignores flags, subcommands and flag values that match no test file', () => {
    const argv = ['node', BIN, 'run', '--reporter', 'verbose', '--coverage', 'watch'];
    expect(testFilters(argv, TEST_FILES)).toEqual([]);
  });

  it('matches Windows-style paths and bins', () => {
    const argv = ['node', 'C:\\repo\\node_modules\\vitest\\vitest.mjs', 'run', 'test\\planner'];
    expect(testFilters(argv, TEST_FILES)).toEqual(['test\\planner']);
  });

  it('falls back to argv[2..] when no vitest bin is found', () => {
    expect(testFilters(['node', 'runner.js', 'helpers', ''], TEST_FILES)).toEqual(['helpers']);
  });
});

describe('scopeThresholds', () => {
  it('leaves thresholds untouched without filters', () => {
    const t = coreThresholds();
    const before = { ...t };
    expect(scopeThresholds(t, [])).toBe(t);
    expect(t).toEqual(before);
  });

  it('drops global thresholds and keeps only globs that mention a filter', () => {
    const t = scopeThresholds(coreThresholds(), ['planner']);
    expect(t).toEqual({
      autoUpdate: false,
      'src/coach/planner.ts': { lines: 99, statements: 99, functions: 100, branches: 96 },
    });
  });

  it('keeps no thresholds for a filter that no glob mentions', () => {
    expect(scopeThresholds(coreThresholds(), ['helpers'])).toEqual({ autoUpdate: false });
  });

  it('drops perFile and the 100 shorthand too', () => {
    const t = scopeThresholds({ perFile: true, 100: true, lines: 50 }, ['planner']);
    expect(t).toEqual({ autoUpdate: false });
  });
});

describe('listTestFiles', () => {
  let dir: string | undefined;
  afterEach(() => {
    if (dir) rmSync(dir, { recursive: true, force: true });
    dir = undefined;
  });

  const fixture = () => {
    dir = mkdtempSync(join(tmpdir(), 'cov-scope-'));
    for (const d of ['test/nested', 'node_modules/x', 'dist', '.turbo', 'src']) {
      mkdirSync(join(dir, d), { recursive: true });
    }
    for (const f of [
      'test/planner.test.ts',
      'test/nested/web.spec.tsx',
      'test/helper.ts',
      'node_modules/x/a.test.ts',
      'dist/b.test.js',
      '.turbo/c.test.ts',
      'src/planner.ts',
    ]) {
      writeFileSync(join(dir, f), '');
    }
    return dir;
  };

  it('finds test and spec files, skipping node_modules, dist and dot dirs', () => {
    expect(listTestFiles(fixture())).toEqual(['test/nested/web.spec.tsx', 'test/planner.test.ts']);
  });

  it('coverageScope narrows the resolved thresholds on a filtered run', () => {
    const root = fixture();
    const thresholds = coreThresholds();
    const plugin = coverageScope(['node', BIN, 'run', 'planner', '--coverage']);
    plugin.configureVitest({ vitest: { config: { root, coverage: { thresholds } } } });
    expect(Object.keys(thresholds).sort()).toEqual(['autoUpdate', 'src/coach/planner.ts']);
  });

  it('coverageScope leaves an unfiltered run and a run without thresholds alone', () => {
    const root = fixture();
    const thresholds = coreThresholds();
    coverageScope(['node', BIN, 'run', '--coverage']).configureVitest({
      vitest: { config: { root, coverage: { thresholds } } },
    });
    expect(thresholds.lines).toBe(92);
    const coverage: { thresholds?: Thresholds } = {};
    coverageScope(['node', BIN, 'run', 'planner']).configureVitest({
      vitest: { config: { root, coverage } },
    });
    expect(coverage).toEqual({});
  });

  it('defaults to process.argv', () => {
    expect(coverageScope().name).toBe('stride:coverage-scope');
  });
});
