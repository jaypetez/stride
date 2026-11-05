import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { analyzeCommand } from '../src/commands/analyze';
import { connectCommand } from '../src/commands/connect';
import { nextCommand } from '../src/commands/next';
import { planCommand } from '../src/commands/plan';
import { syncCommand } from '../src/commands/sync';
import { errorLine, errorMsg } from '../src/ui';

// Failure paths must exit non-zero for scripted/cron runs (AUD-007), and demo
// mode must not depend on the wall clock (AUD-008).
const tmpDirs: string[] = [];
let logSpy: ReturnType<typeof vi.spyOn>;
let errSpy: ReturnType<typeof vi.spyOn>;
const SAVED = { ...process.env };

beforeEach(() => {
  const dir = path.join(
    os.tmpdir(),
    `stride-cli-exit-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  );
  tmpDirs.push(dir);
  process.env.STRIDE_DATA_DIR = dir;
  process.env.STRIDE_NOW = '2026-07-09T00:00:00Z';
  delete process.env.ANTHROPIC_API_KEY;
  delete process.env.STRAVA_CLIENT_ID;
  delete process.env.STRAVA_CLIENT_SECRET;
  process.exitCode = undefined;
  logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
  errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  process.exitCode = undefined;
  vi.restoreAllMocks();
  vi.useRealTimers();
  process.env = { ...SAVED };
});

afterAll(() => {
  for (const dir of tmpDirs) fs.rmSync(dir, { recursive: true, force: true });
});

function lastJson(): any {
  const { calls } = logSpy.mock;
  for (let i = calls.length - 1; i >= 0; i--) {
    const first = calls[i][0];
    if (typeof first === 'string' && first.trim().startsWith('{')) return JSON.parse(first);
  }
  throw new Error('no JSON output');
}

const stderr = () => errSpy.mock.calls.flat().join(' ');

describe('errorMsg sets a failing exit code', () => {
  it('errorMsg -> exitCode 1; errorLine leaves it alone', () => {
    errorLine('just a red line');
    expect(process.exitCode).toBeUndefined();
    errorMsg('boom');
    expect(process.exitCode).toBe(1);
  });
});

describe('every listed failure path exits 1', () => {
  it.each([
    ['analyze: no activities stored', () => analyzeCommand({})],
    ['next: no activities stored', () => nextCommand({})],
    ['plan: unknown race', () => planCommand({ demo: true, race: 'ultra' })],
    ['plan: invalid --weeks', () => planCommand({ demo: true, weeks: 'abc' })],
    ['plan: invalid --start', () => planCommand({ demo: true, start: 'garbage' })],
    ['plan: invalid --date', () => planCommand({ demo: true, date: '2026-02-30' })],
    ['sync: Strava not configured', () => syncCommand({})],
    ['connect: Strava not configured', () => connectCommand()],
  ])('%s', async (_name, run) => {
    await run();
    expect(process.exitCode).toBe(1);
  });

  it('analyze: unknown activity id', async () => {
    const { LocalStore, demoHistory } = await import('@stride/core');
    await new LocalStore(process.env.STRIDE_DATA_DIR as string).saveActivities(demoHistory());
    await analyzeCommand({ id: 'nope' });
    expect(process.exitCode).toBe(1);
    expect(stderr()).toMatch(/not found/);
  });

  it('sync: invalid --pages', async () => {
    process.env.STRAVA_CLIENT_ID = '1';
    process.env.STRAVA_CLIENT_SECRET = 's';
    await syncCommand({ pages: '-3' });
    expect(process.exitCode).toBe(1);
    expect(stderr()).toMatch(/--pages/);
  });

  it('sync: "Sync failed" (not connected) exits 1', async () => {
    process.env.STRAVA_CLIENT_ID = '1';
    process.env.STRAVA_CLIENT_SECRET = 's';
    await syncCommand({});
    expect(process.exitCode).toBe(1);
    expect(stderr()).toMatch(/Sync failed/);
  });

  it('plan: invalid dates are rejected BEFORE planning (nothing printed or saved)', async () => {
    await planCommand({ start: 'not-a-date', json: true });
    expect(() => lastJson()).toThrow();
    expect(stderr()).toMatch(/--start/);
  });
});

describe('success paths still exit 0', () => {
  it.each([
    ['analyze --demo', () => analyzeCommand({ demo: true, json: true })],
    ['next --demo', () => nextCommand({ demo: true, json: true })],
    ['plan --demo', () => planCommand({ demo: true, json: true })],
  ])('%s', async (_name, run) => {
    await run();
    expect(process.exitCode ?? 0).toBe(0);
  });
});

describe('demo mode is anchored to the demo history (AUD-008)', () => {
  it('next --demo shows a future race and a defined phase regardless of the wall clock', async () => {
    delete process.env.STRIDE_NOW;
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2031-01-01T00:00:00Z')); // long after the demo race date
    await nextCommand({ demo: true, json: true });
    const out = lastJson();
    expect(out.daysToRace).toBeGreaterThan(0);
    expect(out.planPhase).toBeDefined();
    expect(out.fitness.date).toBe('2026-07-09');
    expect(out.goal.date).toBe('2026-09-06');
  });

  it('plan --demo is byte-identical across runs under a pinned STRIDE_NOW', async () => {
    await planCommand({ demo: true, json: true });
    const a = JSON.stringify(lastJson());
    await planCommand({ demo: true, json: true });
    const b = JSON.stringify(lastJson());
    expect(a).toBe(b);
    expect(JSON.parse(a).plan.createdAt).toBe('2026-07-09T00:00:00Z');
  });

  it('plan --date aligns the last week to race day with a race session', async () => {
    await planCommand({ demo: true, json: true, weeks: '6', date: '2026-08-30' });
    const { plan, validation } = lastJson();
    expect(validation.valid).toBe(true);
    expect(plan.endDate).toBe('2026-08-30');
    const last = plan.weeks[plan.weeks.length - 1];
    expect(last.days.find((d: any) => d.day === 7).sessions[0].type).toBe('race');
  });
});
