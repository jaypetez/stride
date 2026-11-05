import { mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import pkg from '../package.json' with { type: 'json' };
import { loadAppState } from '../src/app-state';
import { buildCoachContext } from '../src/coach/context';
import { type CoachLLM, isLlmConfigError, suggestNextWorkout } from '../src/coach/index';
import { ConfigError, loadConfig } from '../src/config';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';
import { STRIDE_CORE_VERSION } from '../src/index';
import { LocalStore } from '../src/store/index';

afterEach(() => {
  vi.restoreAllMocks();
});

/** Capture everything the structured logger writes to stderr. */
function captureStderr(): { lines: () => string[] } {
  const chunks: string[] = [];
  vi.spyOn(process.stderr, 'write').mockImplementation((chunk: string | Uint8Array) => {
    chunks.push(String(chunk));
    return true;
  });
  return { lines: () => chunks.join('').split('\n').filter(Boolean) };
}

describe('loadActivities tolerates a corrupt record (AUD-009)', () => {
  it('skips the invalid record with a warn log and returns the rest', async () => {
    const dir = path.join(os.tmpdir(), `stride-corrupt-${process.pid}-${Date.now()}`);
    await mkdir(dir, { recursive: true });
    const good = demoHistory().slice(0, 2);
    await writeFile(
      path.join(dir, 'activities.json'),
      JSON.stringify([good[0], { id: 'broken', distance: 'not-a-number' }, good[1]]),
    );
    const err = captureStderr();
    const loaded = await new LocalStore(dir).loadActivities();
    expect(loaded.map((a) => a.id)).toEqual([good[0].id, good[1].id]);
    const warn = err.lines().find((l) => l.includes('skipping invalid stored activity'));
    expect(warn).toMatch(/^\[warn\]/);
    expect(warn).toContain('broken');
  });
});

describe('LLM auth/config failures log at warn (AUD-009)', () => {
  const context = buildCoachContext({
    activities: [...demoHistory(), demoActivity()],
    profile: DEMO_PROFILE,
    asOfDate: '2026-07-09T00:00:00Z',
  });

  const failing = (err: unknown): CoachLLM => ({
    complete: async () => {
      throw err;
    },
    parse: async () => {
      throw err;
    },
  });

  it('classifies 401/403/404 as config errors, others as transient', () => {
    expect(isLlmConfigError(Object.assign(new Error('bad key'), { status: 401 }))).toBe(true);
    expect(isLlmConfigError(Object.assign(new Error('x'), { name: 'PermissionDeniedError' }))).toBe(
      true,
    );
    expect(isLlmConfigError(Object.assign(new Error('busy'), { status: 529 }))).toBe(false);
    expect(isLlmConfigError(new Error('timeout'))).toBe(false);
    expect(isLlmConfigError(undefined)).toBe(false);
  });

  it('a 401 from Anthropic is logged at WARN and the deterministic fallback is used', async () => {
    const err = captureStderr();
    const authError = Object.assign(new Error('401 invalid x-api-key'), {
      status: 401,
      name: 'AuthenticationError',
    });
    const w = await suggestNextWorkout({
      context,
      profile: DEMO_PROFILE,
      deps: { llm: failing(authError) },
    });
    expect(w.rationale.length).toBeGreaterThan(0);
    const line = err.lines().find((l) => l.includes('next-workout rationale failed'));
    expect(line).toMatch(/^\[warn\]/);
    expect(line).toContain('ANTHROPIC_API_KEY');
  });

  it('a transient failure stays below the default (warn) level', async () => {
    const prev = process.env.STRIDE_LOG;
    delete process.env.STRIDE_LOG;
    try {
      const err = captureStderr();
      await suggestNextWorkout({
        context,
        profile: DEMO_PROFILE,
        deps: { llm: failing(Object.assign(new Error('overloaded'), { status: 529 })) },
      });
      expect(err.lines().some((l) => l.includes('next-workout rationale failed'))).toBe(false);
    } finally {
      if (prev !== undefined) process.env.STRIDE_LOG = prev;
    }
  });
});

describe('config validation fails fast (AUD-009)', () => {
  it.each(['abc', '0', '70000', '80.5', '-1'])('rejects STRIDE_API_PORT=%s', (port) => {
    expect(() => loadConfig({ STRIDE_API_PORT: port })).toThrow(ConfigError);
    expect(() => loadConfig({ STRIDE_API_PORT: port })).toThrow(/STRIDE_API_PORT/);
  });

  it.each(['tomorrow', 'not-a-date', '2026-13-45T00:00:00Z'])('rejects STRIDE_NOW=%s', (now) => {
    expect(() => loadConfig({ STRIDE_NOW: now })).toThrow(/STRIDE_NOW/);
  });

  it('accepts valid values and treats empty strings as unset', () => {
    const c = loadConfig({ STRIDE_API_PORT: '9999', STRIDE_NOW: '2026-07-09T00:00:00Z' });
    expect(c.apiPort).toBe(9999);
    expect(c.now).toBe('2026-07-09T00:00:00Z');
    const d = loadConfig({ STRIDE_API_PORT: '', STRIDE_NOW: '' });
    expect(d.apiPort).toBe(8720);
    expect(d.now).toBeUndefined();
  });

  it('loadAppState (shared by API/MCP/CLI) surfaces the same error', () => {
    expect(() => loadAppState({ STRIDE_API_PORT: 'NaN' })).toThrow(ConfigError);
    const state = loadAppState({ STRIDE_DATA_DIR: path.join(os.tmpdir(), 'stride-appstate') });
    expect(state.store).toBeInstanceOf(LocalStore);
    expect(state.llm).toBeNull();
  });
});

describe('version (AUD-009)', () => {
  it('STRIDE_CORE_VERSION comes from package.json', () => {
    expect(STRIDE_CORE_VERSION).toBe(pkg.version);
  });
});
