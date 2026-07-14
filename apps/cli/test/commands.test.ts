import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { LocalStore } from '@stride/core';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { analyzeCommand } from '../src/commands/analyze';
import { doctorCommand } from '../src/commands/doctor';
import { nextCommand } from '../src/commands/next';
import { planCommand } from '../src/commands/plan';
import { disconnectCommand, profileCommand } from '../src/commands/profile';

// Every command calls loadApp() -> loadConfig(process.env). We point the data
// dir at a throwaway tmp path and pin the clock via STRIDE_NOW so demo output is
// deterministic, and clear ANTHROPIC_API_KEY so the coach uses the offline
// deterministic fallback (no network, no secrets).
const tmpDirs: string[] = [];
let logSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  const dir = path.join(
    os.tmpdir(),
    `stride-cli-test-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  );
  tmpDirs.push(dir);
  process.env.STRIDE_DATA_DIR = dir;
  process.env.STRIDE_NOW = '2026-07-09T00:00:00Z';
  delete process.env.ANTHROPIC_API_KEY;
  logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

afterAll(() => {
  for (const dir of tmpDirs) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

/** The JSON commands print exactly one JSON blob via console.log; find it. */
function lastJson(): any {
  const { calls } = logSpy.mock;
  for (let i = calls.length - 1; i >= 0; i--) {
    const first = calls[i][0];
    if (typeof first === 'string') {
      const s = first.trim();
      if (s.startsWith('{') || s.startsWith('[')) {
        try {
          return JSON.parse(s);
        } catch {
          // keep looking
        }
      }
    }
  }
  throw new Error('no JSON console.log output captured');
}

/** All console.log output flattened to a single string. */
function output(): string {
  return logSpy.mock.calls.map((c: unknown[]) => c.map(String).join(' ')).join('\n');
}

describe('analyzeCommand', () => {
  it('prints JSON metrics with a positive training load in demo mode', async () => {
    await analyzeCommand({ demo: true, json: true });
    const out = lastJson();
    expect(out.metrics.tss).toBeGreaterThan(0);
    expect(out.analysis.headline).toContain('TSS');
  });
});

describe('nextCommand', () => {
  it('prints JSON with a workout type in demo mode', async () => {
    await nextCommand({ demo: true, json: true });
    const out = lastJson();
    expect(out.workout.type).toBeDefined();
    expect(out.fitness).toBeDefined();
  });
});

describe('planCommand', () => {
  it('prints a valid 6-week plan in demo mode', async () => {
    await planCommand({ demo: true, race: '10k', weeks: '6', json: true });
    const out = lastJson();
    expect(out.plan.weeks).toHaveLength(6);
    expect(out.validation.valid).toBe(true);
  });
});

describe('doctorCommand', () => {
  it('reports the environment and completes without throwing', async () => {
    await expect(doctorCommand()).resolves.toBeUndefined();
    expect(output()).toContain('Environment');
  });
});

describe('profileCommand', () => {
  it('prints the athlete profile without throwing', async () => {
    await expect(profileCommand()).resolves.toBeUndefined();
    expect(output()).toContain('Athlete profile');
  });
});

describe('disconnectCommand', () => {
  it('attempts to revoke on Strava, then still deletes local tokens when the revoke fails offline', async () => {
    const store = new LocalStore(process.env.STRIDE_DATA_DIR as string);
    await store.saveTokens({ accessToken: 'tok-xyz', refreshToken: 'r', expiresAt: 123 });

    // Simulate an offline environment: the deauthorize call cannot reach Strava.
    const fetchMock = vi.fn(async (_url: string) => {
      throw new Error('getaddrinfo ENOTFOUND www.strava.com');
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(disconnectCommand({})).resolves.toBeUndefined();

    // The revoke was attempted (best-effort) against the OAuth endpoint...
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toBe('https://www.strava.com/oauth/deauthorize');
    // ...and local tokens were still removed despite the failure.
    expect(await store.loadTokens()).toBeNull();
    expect(output()).toContain('Could not revoke access on Strava');
  });

  it('does not attempt a revoke when there is no local token', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(disconnectCommand({})).resolves.toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
