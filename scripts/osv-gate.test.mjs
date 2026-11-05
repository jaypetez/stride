import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { evaluate } from './osv-gate.mjs';

// Trimmed osv-scanner `--format json` reports (real shape, fixture data).
function report(...packages) {
  return { results: [{ source: { path: 'pnpm-lock.yaml', type: 'lockfile' }, packages }] };
}
function pkg(name, version, id, maxSeverity, ghsaSeverity) {
  return {
    package: { name, version, ecosystem: 'npm' },
    vulnerabilities: [{ id, database_specific: { severity: ghsaSeverity } }],
    groups: [{ ids: [id], aliases: [id], max_severity: maxSeverity }],
  };
}

const VULNERABLE = report(pkg('lodash', '4.17.20', 'GHSA-35jh-r3h4-6jhm', '7.2', 'HIGH'));
const MODERATE_ONLY = report(pkg('qs', '6.16.0', 'GHSA-6pq8-jhm2-2vcw', '6.3', 'MODERATE'));

test('a known High advisory fails the gate', () => {
  const { ok, lines } = evaluate(VULNERABLE);
  assert.equal(ok, false);
  assert.match(lines[0], /lodash@4\.17\.20 GHSA-35jh-r3h4-6jhm/);
});

test('Moderate and Low advisories are reported but pass', () => {
  const { ok, lines } = evaluate(MODERATE_ONLY);
  assert.equal(ok, true);
  assert.equal(lines.length, 1);
});

test('an unscored advisory labelled CRITICAL still fails', () => {
  assert.equal(evaluate(report(pkg('x', '1.0.0', 'GHSA-x', '', 'CRITICAL'))).ok, false);
});

test('a clean report passes', () => {
  assert.equal(evaluate({ results: [] }).ok, true);
  assert.equal(evaluate({}).ok, true);
});

test('the CLI exits non-zero on the vulnerable fixture', () => {
  const dir = mkdtempSync(join(tmpdir(), 'osv-gate-'));
  const bad = join(dir, 'bad.json');
  const good = join(dir, 'good.json');
  writeFileSync(bad, JSON.stringify(VULNERABLE));
  writeFileSync(good, JSON.stringify(MODERATE_ONLY));
  const script = fileURLToPath(new URL('./osv-gate.mjs', import.meta.url));
  assert.equal(spawnSync(process.execPath, [script, bad]).status, 1);
  assert.equal(spawnSync(process.execPath, [script, good]).status, 0);
});
