import { describe, expect, it } from 'vitest';
// A tiny ASCII-only module (no node built-ins, no Unicode) so it imports
// cleanly under vitest on every platform — unlike the root smoke harness.
// @ts-expect-error - plain .mjs script with no type declarations
import { scrubbedEnv } from '../../../scripts/child-env.mjs';

describe('smoke harness child env (bug 8: offline/deterministic verify)', () => {
  it('scrubs ANTHROPIC_API_KEY and STRAVA_* so demo paths stay offline', () => {
    const base = {
      ANTHROPIC_API_KEY: 'sk-should-be-scrubbed',
      STRAVA_CLIENT_ID: '12345',
      STRAVA_CLIENT_SECRET: 'secret',
      PATH: '/usr/bin',
      HOME: '/home/dev',
    };
    const env = scrubbedEnv(base, { STRIDE_NOW: '2026-07-14T12:00:00Z', STRIDE_DATA_DIR: '.x' });

    expect(env.ANTHROPIC_API_KEY).toBeUndefined();
    expect(env.STRAVA_CLIENT_ID).toBeUndefined();
    expect(env.STRAVA_CLIENT_SECRET).toBeUndefined();
    // Deterministic pins are injected; unrelated vars survive.
    expect(env.STRIDE_NOW).toBe('2026-07-14T12:00:00Z');
    expect(env.STRIDE_DATA_DIR).toBe('.x');
    expect(env.PATH).toBe('/usr/bin');
    expect(env.HOME).toBe('/home/dev');
  });

  it('lets caller-supplied extra env through', () => {
    const env = scrubbedEnv({}, {}, { STRIDE_API_PORT: '9999' });
    expect(env.STRIDE_API_PORT).toBe('9999');
  });
});
