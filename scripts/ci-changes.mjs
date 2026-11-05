#!/usr/bin/env node
// Path filter for CI (GOV-001). Writes `android`, `cloud` and `web` booleans to
// $GITHUB_OUTPUT based on the files changed by the pull request. Non-PR events
// (push to main, workflow_dispatch) mark every surface as changed.
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';

export const FILTERS = {
  android: [/^android\//, /^contracts\//, /^\.github\/workflows\/(android|ci)\.yml$/],
  cloud: [/^apps\/cloud\//, /^packages\//, /^contracts\//],
  web: [/^apps\/web\//, /^packages\//],
};

export function classify(files) {
  const out = {};
  for (const [name, patterns] of Object.entries(FILTERS)) {
    out[name] = files.some((f) => patterns.some((re) => re.test(f)));
  }
  return out;
}

function changedFiles(baseSha) {
  const out = execFileSync('git', ['diff', '--name-only', `${baseSha}...HEAD`], {
    encoding: 'utf8',
  });
  return out.split('\n').filter(Boolean);
}

function main() {
  const event = process.env.EVENT ?? '';
  const base = process.env.BASE_SHA ?? '';
  const result =
    event === 'pull_request' && base
      ? classify(changedFiles(base))
      : Object.fromEntries(Object.keys(FILTERS).map((k) => [k, true]));
  const lines = Object.entries(result).map(([k, v]) => `${k}=${v}`);
  console.log(lines.join('\n'));
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${lines.join('\n')}\n`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
