import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { collectRows, parseThresholds, renderTable } from './coverage-summary.mjs';

const CONFIG = `export default defineConfig({ test: { coverage: { thresholds: {
  autoUpdate: process.env.COVERAGE_RATCHET ? (n) => Math.floor(n) : false,
  lines: 85,
  statements: 84.5,
  functions: 80,
  branches: 69,
} } } });`;

const summary = (pct) => ({
  total: Object.fromEntries(
    ['lines', 'statements', 'functions', 'branches'].map((m) => [m, { pct: pct[m] ?? 0 }]),
  ),
});

function fixture() {
  const root = mkdtempSync(path.join(os.tmpdir(), 'cov-summary-'));
  const add = (rel, name, pct, config) => {
    const dir = path.join(root, rel);
    mkdirSync(path.join(dir, 'coverage'), { recursive: true });
    writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name }));
    writeFileSync(path.join(dir, 'coverage', 'coverage-summary.json'), JSON.stringify(summary(pct)));
    if (config) writeFileSync(path.join(dir, 'vitest.config.ts'), config);
  };
  add('packages/core', '@stride/core', { lines: 90, statements: 88, functions: 81, branches: 70 }, CONFIG);
  add('apps/api', '@stride/api', { lines: 50, statements: 50, functions: 50, branches: 50 }, CONFIG);
  add('apps/plain', '@stride/plain', { lines: 10, statements: 10, functions: 10, branches: 10 });
  // A package that never ran coverage is skipped, not reported as 0%.
  mkdirSync(path.join(root, 'tools', 'none'), { recursive: true });
  writeFileSync(path.join(root, 'tools', 'none', 'package.json'), '{"name":"none"}');
  return root;
}

test('parseThresholds reads numeric thresholds and ignores autoUpdate', () => {
  assert.deepEqual(parseThresholds(CONFIG), {
    lines: 85,
    statements: 84.5,
    functions: 80,
    branches: 69,
  });
  assert.deepEqual(parseThresholds('export default {}'), {});
});

test('parseThresholds ignores nested glob thresholds wherever they appear', () => {
  const source = `defineConfig({ test: { coverage: { thresholds: {
    'src/coach/planner.ts': { lines: 99, statements: 99, functions: 100, branches: 96 },
    lines: 92,
    statements: 89,
    'src/x/**': { functions: 1 },
    functions: 88,
    branches: 79,
  } } } });`;
  assert.deepEqual(parseThresholds(source), {
    lines: 92,
    statements: 89,
    functions: 88,
    branches: 79,
  });
});

test('collectRows pairs each summary with its thresholds and skips missing ones', () => {
  const root = fixture();
  try {
    const rows = collectRows(root);
    // packages/* first, then apps/*, then tools/* (each sorted by directory).
    assert.deepEqual(
      rows.map((r) => r.name),
      ['@stride/core', '@stride/api', '@stride/plain'],
    );
    assert.equal(rows[0].pct.lines, 90);
    assert.equal(rows[0].thresholds.lines, 85);
    assert.deepEqual(rows[2].thresholds, {});
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('renderTable marks passes and misses and names the failing packages', () => {
  const root = fixture();
  try {
    const md = renderTable(collectRows(root));
    assert.match(md, /\| Package \| Lines \| Statements \| Functions \| Branches \|/);
    assert.match(md, /`@stride\/core` \| ✅ 90\.00% \(min 85%\)/);
    assert.match(md, /`@stride\/api` \| ❌ 50\.00% \(min 85%\)/);
    assert.match(md, /`@stride\/plain` \| 10\.00% \|/);
    assert.match(md, /\*\*Below threshold:\*\* `@stride\/api`\./);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('renderTable reports an all-green run and an empty run', () => {
  const green = renderTable([
    { name: 'x', pct: { lines: 1, statements: 1, functions: 1, branches: 1 }, thresholds: { lines: 1 } },
  ]);
  assert.match(green, /All packages meet their coverage thresholds/);
  assert.match(renderTable([]), /No coverage summaries found/);
});
