#!/usr/bin/env node
/**
 * Ratchet coverage thresholds up: re-run `pnpm coverage` with
 * COVERAGE_RATCHET=1, which turns on Vitest's `thresholds.autoUpdate` (floored
 * to whole percentages) in every vitest config. Thresholds only ever rise.
 * Commit the rewritten configs. Policy: docs/testing/coverage.md.
 */
import { spawnSync } from 'node:child_process';

const result = spawnSync('pnpm', ['coverage'], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
  env: { ...process.env, COVERAGE_RATCHET: '1' },
});
process.exit(result.status ?? 1);
