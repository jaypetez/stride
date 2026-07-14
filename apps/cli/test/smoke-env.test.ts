import { afterEach, describe, expect, it } from 'vitest';
// The verify/smoke harness lives at the repo root. Importing it must be
// side-effect-free (its `main()` is guarded to run only when invoked directly).
// @ts-expect-error - smoke.mjs is a plain root script with no type declarations
import { childEnv } from '../../../scripts/smoke.mjs';

const saved = { ...process.env };
afterEach(() => {
  process.env = { ...saved };
});

describe('smoke harness child env (bug 8: offline/deterministic verify)', () => {
  it('scrubs ANTHROPIC_API_KEY and STRAVA_* so demo paths stay offline', () => {
    process.env.ANTHROPIC_API_KEY = 'sk-should-be-scrubbed';
    process.env.STRAVA_CLIENT_ID = '12345';
    process.env.STRAVA_CLIENT_SECRET = 'secret';

    const env = childEnv();

    expect(env.ANTHROPIC_API_KEY).toBeUndefined();
    expect(env.STRAVA_CLIENT_ID).toBeUndefined();
    expect(env.STRAVA_CLIENT_SECRET).toBeUndefined();
    // Deterministic pins are still injected, and unrelated vars survive.
    expect(env.STRIDE_NOW).toBeTruthy();
    expect(env.STRIDE_DATA_DIR).toBeTruthy();
    expect(env.PATH ?? env.Path).toBeDefined();
  });

  it('lets caller-supplied extra env through', () => {
    const env = childEnv({ STRIDE_API_PORT: '9999' });
    expect(env.STRIDE_API_PORT).toBe('9999');
  });
});
