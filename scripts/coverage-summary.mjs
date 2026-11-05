#!/usr/bin/env node
/**
 * Render a Markdown coverage table from every Vitest `coverage-summary.json`
 * (json-summary reporter) in the workspace, alongside each package's enforced
 * `coverage.thresholds`. CI appends the output to `$GITHUB_STEP_SUMMARY`:
 *
 *   node scripts/coverage-summary.mjs >> "$GITHUB_STEP_SUMMARY"
 *
 * Enforcement itself is Vitest's job (`pnpm coverage` exits non-zero on a miss);
 * this only reports. Policy: docs/testing/coverage.md.
 *
 * Dependency-free so it runs (and is unit-tested by `node --test`) anywhere.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const METRICS = /** @type {const} */ (['lines', 'statements', 'functions', 'branches']);

/** Workspace dirs that own a Vitest suite (relative to the repo root). */
const WORKSPACE_GLOBS = ['packages', 'apps', 'tools'];

/**
 * The top level of the `thresholds: { ... }` object, with nested objects (glob
 * thresholds such as `'src/coach/planner.ts': { lines: 99 }`) blanked out so
 * only the package-wide numbers are read.
 * @param {string} source
 */
function topLevelBlock(source) {
  const start = /thresholds\s*:\s*\{/.exec(source);
  if (!start) return '';
  let depth = 1;
  let out = '';
  for (let i = start.index + start[0].length; i < source.length; i++) {
    const ch = source[i];
    if (ch === '{') depth++;
    else if (ch === '}' && --depth === 0) break;
    else if (depth === 1) out += ch;
  }
  return out;
}

/**
 * Parse the numeric `thresholds` out of a vitest config's source. Returns only
 * the metrics it finds (a config without thresholds yields `{}`).
 * @param {string} source
 * @returns {Partial<Record<(typeof METRICS)[number], number>>}
 */
export function parseThresholds(source) {
  const block = topLevelBlock(source);
  /** @type {Partial<Record<(typeof METRICS)[number], number>>} */
  const out = {};
  for (const m of METRICS) {
    const hit = new RegExp(`\\b${m}\\s*:\\s*(\\d+(?:\\.\\d+)?)`).exec(block);
    if (hit) out[m] = Number(hit[1]);
  }
  return out;
}

/**
 * @typedef {{ name: string, pct: Record<string, number>, thresholds: Record<string, number> }} Row
 */

/**
 * Find every suite's summary + thresholds under `root`.
 * @param {string} root
 * @returns {Row[]}
 */
export function collectRows(root) {
  /** @type {Array<{ name: string, summary: string, config?: string }>} */
  const suites = [];
  for (const group of WORKSPACE_GLOBS) {
    const groupDir = path.join(root, group);
    if (!existsSync(groupDir)) continue;
    for (const entry of readdirSync(groupDir).sort()) {
      const dir = path.join(groupDir, entry);
      const pkgFile = path.join(dir, 'package.json');
      if (!existsSync(pkgFile)) continue;
      const name = JSON.parse(readFileSync(pkgFile, 'utf8')).name ?? `${group}/${entry}`;
      const config = ['vitest.config.ts', 'vitest.config.mts']
        .map((f) => path.join(dir, f))
        .find((f) => existsSync(f));
      suites.push({ name, summary: path.join(dir, 'coverage', 'coverage-summary.json'), config });
    }
  }
  suites.push({
    name: 'scripts (root)',
    summary: path.join(root, 'coverage', 'scripts', 'coverage-summary.json'),
    config: path.join(root, 'vitest.config.mts'),
  });

  /** @type {Row[]} */
  const rows = [];
  for (const s of suites) {
    if (!existsSync(s.summary)) continue;
    const total = JSON.parse(readFileSync(s.summary, 'utf8')).total ?? {};
    /** @type {Record<string, number>} */
    const pct = {};
    for (const m of METRICS) pct[m] = Number(total[m]?.pct ?? 0);
    const thresholds =
      s.config && existsSync(s.config) ? parseThresholds(readFileSync(s.config, 'utf8')) : {};
    rows.push({ name: s.name, pct, thresholds });
  }
  return rows;
}

/**
 * @param {number} pct
 * @param {number | undefined} min
 */
function cell(pct, min) {
  const value = `${pct.toFixed(2)}%`;
  if (min === undefined) return value;
  return `${pct >= min ? '✅' : '❌'} ${value} (min ${min}%)`;
}

/**
 * Render rows as a GitHub-flavoured Markdown table.
 * @param {Row[]} rows
 * @returns {string}
 */
export function renderTable(rows) {
  const lines = ['## Coverage', ''];
  if (rows.length === 0) {
    lines.push('_No coverage summaries found. Did `pnpm coverage` run?_');
    return `${lines.join('\n')}\n`;
  }
  lines.push(`| Package | ${METRICS.map((m) => m[0].toUpperCase() + m.slice(1)).join(' | ')} |`);
  lines.push(`|---|${METRICS.map(() => '---:').join('|')}|`);
  for (const r of rows) {
    lines.push(
      `| \`${r.name}\` | ${METRICS.map((m) => cell(r.pct[m], r.thresholds[m])).join(' | ')} |`,
    );
  }
  const misses = rows.filter((r) =>
    METRICS.some((m) => r.thresholds[m] !== undefined && r.pct[m] < r.thresholds[m]),
  );
  lines.push('');
  lines.push(
    misses.length === 0
      ? 'All packages meet their coverage thresholds. Policy: `docs/testing/coverage.md`.'
      : `**Below threshold:** ${misses.map((r) => `\`${r.name}\``).join(', ')}. Policy: \`docs/testing/coverage.md\`.`,
  );
  return `${lines.join('\n')}\n`;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  process.stdout.write(renderTable(collectRows(root)));
}
