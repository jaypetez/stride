#!/usr/bin/env node
// The aggregate `build` gate (GOV-001): reads `toJSON(needs)` from $RESULTS and
// fails unless every job succeeded. Path-filtered jobs may be `skipped`.
const PATH_FILTERED = new Set(['android']);

export function evaluate(needs) {
  const lines = [];
  let ok = true;
  for (const [job, { result }] of Object.entries(needs)) {
    const pass = result === 'success' || (result === 'skipped' && PATH_FILTERED.has(job));
    lines.push(`${pass ? '✓' : '✗'} ${job}: ${result}`);
    if (!pass) ok = false;
  }
  return { ok, lines };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { ok, lines } = evaluate(JSON.parse(process.env.RESULTS ?? '{}'));
  console.log(lines.join('\n'));
  if (!ok) {
    console.log('::error::A required CI job did not succeed.');
    process.exit(1);
  }
  console.log('All CI jobs passed.');
}
