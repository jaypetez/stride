#!/usr/bin/env node
// Dependency vulnerability gate (AUD-024). `pnpm audit` called npm's retired
// audit endpoint, so CI runs osv-scanner over pnpm-lock.yaml instead and pipes
// its JSON report here. The gate fails on any High or Critical advisory (CVSS
// >= 7.0, or a GHSA severity of HIGH/CRITICAL) and lists lower ones as notices,
// matching the old `--audit-level=high` bar and dependency-review's threshold.
//
//   osv-scanner scan source --format json --lockfile pnpm-lock.yaml > osv.json
//   node scripts/osv-gate.mjs osv.json
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const HIGH_CVSS = 7.0;
const HIGH_LABELS = new Set(['HIGH', 'CRITICAL']);

/** Highest CVSS score and GHSA label for one osv-scanner group of aliases. */
function groupSeverity(group, vulns) {
  const score = Number.parseFloat(group.max_severity ?? '');
  const labels = vulns
    .filter((v) => group.ids?.includes(v.id))
    .map((v) => String(v.database_specific?.severity ?? '').toUpperCase())
    .filter(Boolean);
  return { score: Number.isFinite(score) ? score : null, labels };
}

/**
 * Evaluate an osv-scanner JSON report. Returns `{ ok, lines }` where `ok` is
 * false when any advisory is High or Critical.
 */
export function evaluate(report, threshold = HIGH_CVSS) {
  const lines = [];
  let ok = true;
  for (const result of report?.results ?? []) {
    for (const pkg of result.packages ?? []) {
      const name = `${pkg.package?.name}@${pkg.package?.version}`;
      const vulns = pkg.vulnerabilities ?? [];
      for (const group of pkg.groups ?? []) {
        const { score, labels } = groupSeverity(group, vulns);
        const high =
          (score !== null && score >= threshold) || labels.some((l) => HIGH_LABELS.has(l));
        const sev = [score ?? 'unscored', ...new Set(labels)].join(' ');
        lines.push(`${high ? '✗' : '!'} ${name} ${group.ids?.join(', ')} (${sev})`);
        if (high) ok = false;
      }
    }
  }
  if (lines.length === 0) lines.push('✓ no known vulnerabilities');
  return { ok, lines };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const file = process.argv[2];
  if (!file) {
    console.error('usage: node scripts/osv-gate.mjs <osv-report.json>');
    process.exit(2);
  }
  const { ok, lines } = evaluate(JSON.parse(readFileSync(file, 'utf8')));
  console.log(lines.join('\n'));
  if (!ok) {
    console.log('::error::High or Critical dependency vulnerabilities found (osv-scanner).');
    process.exit(1);
  }
  console.log('No High or Critical dependency vulnerabilities.');
}
