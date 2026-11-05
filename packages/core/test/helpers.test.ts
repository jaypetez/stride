import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  AthleteProfile,
  type CoachContext,
  type DailyLoad,
  RaceGoal,
  type Zones,
} from '@stride/schemas';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  type AnthropicLike,
  buildAnalyzePrompt,
  buildClassifyPrompt,
  buildCoachContext,
  buildNextPrompt,
  buildPlanProposalPrompt,
  buildPlanSkeleton,
  buildPlanSummaryPrompt,
  COACH_TOOLS,
  type CoachDataProvider,
  createCoachLLM,
  findCoachTool,
  formatContext,
  formatMetrics,
  logLlmCall,
  runCoachTool,
  toLlmUsage,
} from '../src/coach/index';
import { loadConfig, type StravaConfig } from '../src/config';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';
import { createLogger, logger } from '../src/log';
import {
  addDays,
  computeActivityMetrics,
  computeZones,
  daysBetween,
  eachDay,
  interpolate,
  rollingMeanByTime,
  toDateKey,
} from '../src/science/index';
import { LocalStore } from '../src/store/index';
import {
  buildAuthorizeUrl,
  exchangeCodeForTokens,
  mapActivity,
  mapSportType,
  mapStreams,
  refreshTokens,
} from '../src/strava/index';

// --- Dates ------------------------------------------------------------------

describe('date helpers (UTC keys, DST-safe)', () => {
  it('addDays crosses the US and EU DST transitions without drift', () => {
    // US spring-forward 2026-03-08, EU 2026-03-29, US fall-back 2026-11-01.
    expect(addDays('2026-03-07', 1)).toBe('2026-03-08');
    expect(addDays('2026-03-08', 1)).toBe('2026-03-09');
    expect(addDays('2026-03-28', 2)).toBe('2026-03-30');
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addDays('2026-11-01', 1)).toBe('2026-11-02');
    expect(addDays('2026-11-02', -2)).toBe('2026-10-31');
    expect(daysBetween('2026-03-01', '2026-04-01')).toBe(31);
    expect(daysBetween('2026-10-25', '2026-11-08')).toBe(14);
  });

  it('is independent of the process timezone', () => {
    const prev = process.env.TZ;
    try {
      for (const tz of ['America/New_York', 'Europe/London', 'Australia/Sydney', 'UTC']) {
        process.env.TZ = tz;
        expect(addDays('2026-03-08', 1), tz).toBe('2026-03-09');
        expect(eachDay('2026-10-24', '2026-10-26'), tz).toEqual([
          '2026-10-24',
          '2026-10-25',
          '2026-10-26',
        ]);
      }
    } finally {
      if (prev === undefined) delete process.env.TZ;
      else process.env.TZ = prev;
    }
  });

  it('handles 29 February in leap and non-leap years', () => {
    expect(addDays('2024-02-28', 1)).toBe('2024-02-29');
    expect(addDays('2024-02-29', 1)).toBe('2024-03-01');
    expect(addDays('2025-02-28', 1)).toBe('2025-03-01');
    expect(addDays('2024-02-29', 365)).toBe('2025-02-28');
    expect(addDays('2024-03-01', -1)).toBe('2024-02-29');
    expect(daysBetween('2024-02-01', '2024-03-01')).toBe(29);
    expect(daysBetween('2025-02-01', '2025-03-01')).toBe(28);
    expect(daysBetween('2100-02-28', '2100-03-01')).toBe(1); // 2100 is not a leap year
    expect(eachDay('2024-02-27', '2024-03-01')).toEqual([
      '2024-02-27',
      '2024-02-28',
      '2024-02-29',
      '2024-03-01',
    ]);
  });

  it('normalizes full ISO datetimes to their (local) calendar date', () => {
    expect(toDateKey('2026-03-08T02:30:00-05:00')).toBe('2026-03-08');
    expect(toDateKey('2026-03-08T23:59:59.999Z')).toBe('2026-03-08');
    expect(toDateKey('2024-02-29T12:00:00+13:00')).toBe('2024-02-29');
    expect(toDateKey('2026-07-01')).toBe('2026-07-01');
    expect(addDays(toDateKey('2024-02-28T22:00:00Z'), 1)).toBe('2024-02-29');
    expect(daysBetween(toDateKey('2026-01-01T00:00:00Z'), toDateKey('2026-12-31T23:59:59Z'))).toBe(
      364,
    );
  });

  it('eachDay is inclusive, single-day for equal bounds and empty when reversed', () => {
    expect(eachDay('2026-12-30', '2027-01-02')).toEqual([
      '2026-12-30',
      '2026-12-31',
      '2027-01-01',
      '2027-01-02',
    ]);
    expect(eachDay('2026-05-05', '2026-05-05')).toEqual(['2026-05-05']);
    expect(eachDay('2026-05-06', '2026-05-05')).toEqual([]);
  });
});

// --- Numeric utilities --------------------------------------------------------

describe('rollingMeanByTime', () => {
  it('averages samples within the trailing time window', () => {
    const out = rollingMeanByTime([10, 20, 30, 40], [0, 1, 2, 3], 1);
    expect(out).toEqual([10, 15, 25, 35]);
  });

  it('handles uneven sampling and an empty input', () => {
    // At t=10 the window (5s) excludes t=0 and t=2.
    expect(rollingMeanByTime([4, 8, 12], [0, 2, 10], 5)).toEqual([4, 6, 12]);
    expect(rollingMeanByTime([], [], 30)).toEqual([]);
  });

  it('a window wider than the series is a cumulative mean', () => {
    expect(rollingMeanByTime([1, 2, 3], [0, 1, 2], 100)).toEqual([1, 1.5, 2]);
  });
});

describe('interpolate', () => {
  const table = [
    [0, 0],
    [10, 100],
    [20, 300],
  ] as const;

  it('interpolates linearly between points', () => {
    expect(interpolate(5, table)).toBe(50);
    expect(interpolate(15, table)).toBe(200);
    expect(interpolate(10, table)).toBe(100);
  });

  it('clamps outside the table and returns 0 for an empty table', () => {
    expect(interpolate(-5, table)).toBe(0);
    expect(interpolate(25, table)).toBe(300);
    expect(interpolate(5, [])).toBe(0);
  });
});

describe('computeZones', () => {
  it('derives five HR and five pace zones from full anchors', () => {
    const z = computeZones(DEMO_PROFILE);
    expect(z.hr).toHaveLength(5);
    expect(z.pace).toHaveLength(5);
    // Contiguous, increasing HR bounds that top out at max HR.
    for (let i = 1; i < z.hr.length; i++) expect(z.hr[i].minBpm).toBe(z.hr[i - 1].maxBpm);
    expect(z.hr.at(-1)?.maxBpm).toBe(188);
    expect(z.pace.map((p) => p.label)).toEqual(['E', 'M', 'T', 'I', 'R']);
    expect(z.pace[2].minSpeedMps).toBeCloseTo(0.94 * 3.33, 3);
  });

  it('estimates HR zones from age alone and pace zones from vdot alone', () => {
    const z = computeZones(AthleteProfile.parse({ age: 40, vdot: 45 }));
    expect(z.hr).toHaveLength(5);
    expect(z.hr[4].minBpm).toBe(Math.round(0.9 * (208 - 0.7 * 40)));
    expect(z.pace).toHaveLength(5);
  });

  it('returns empty zones when no anchors are known', () => {
    expect(computeZones(AthleteProfile.parse({}))).toEqual({ hr: [], pace: [] });
  });
});

// --- Local store --------------------------------------------------------------

describe('LocalStore.upsertDailyLoads', () => {
  let dir: string;
  beforeEach(async () => {
    dir = await mkdtemp(path.join(os.tmpdir(), 'stride-upsert-'));
  });
  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  const load = (date: string, tss: number, ids: string[] = [`a-${date}`]): DailyLoad => ({
    date,
    tss,
    durationSec: tss * 36,
    distanceM: tss * 100,
    method: 'rtss',
    activityIds: ids,
  });

  it('merges recomputed days over history, sorted, and persists', async () => {
    const store = new LocalStore(dir);
    await store.saveDailyLoads([load('2026-01-03', 30), load('2026-01-01', 10)]);
    const merged = await store.upsertDailyLoads(
      [load('2026-01-03', 50), load('2026-01-02', 20)],
      '2000-01-01',
    );
    expect(merged.map((d) => [d.date, d.tss])).toEqual([
      ['2026-01-01', 10],
      ['2026-01-02', 20],
      ['2026-01-03', 50],
    ]);
    expect(await store.loadDailyLoads()).toEqual(merged);
  });

  it('drops activity ids older than the retention cutoff but keeps the aggregate', async () => {
    const store = new LocalStore(dir);
    const merged = await store.upsertDailyLoads(
      [load('2026-01-01', 10), load('2026-01-10', 40)],
      '2026-01-05',
    );
    expect(merged[0]).toMatchObject({ date: '2026-01-01', tss: 10, activityIds: [] });
    expect(merged[1].activityIds).toEqual(['a-2026-01-10']);
  });

  it('deletes authoritative dates that the recompute no longer produces', async () => {
    const store = new LocalStore(dir);
    await store.saveDailyLoads([load('2026-02-01', 10), load('2026-02-02', 20)]);
    const merged = await store.upsertDailyLoads([], '2000-01-01', ['2026-02-02']);
    expect(merged.map((d) => d.date)).toEqual(['2026-02-01']);
  });
});

// --- Strava mapping & OAuth ----------------------------------------------------

describe('mapSportType', () => {
  it.each([
    ['Run', 'run'],
    ['VirtualRun', 'treadmill_run'],
    ['TrailRun', 'trail_run'],
    ['Treadmill', 'treadmill_run'],
    ['Walk', 'walk'],
    ['Hike', 'hike'],
    ['Ride', 'other'],
    [undefined, 'other'],
  ])('%s -> %s', (input, expected) => {
    expect(mapSportType(input)).toBe(expected);
  });
});

describe('mapStreams', () => {
  it('maps every key_by_type stream to its camelCase field', () => {
    const s = mapStreams({
      time: { data: [0, 1] },
      distance: { data: [0, 3] },
      altitude: { data: [10, 11] },
      velocity_smooth: { data: [3, 3] },
      heartrate: { data: [140, 141] },
      cadence: { data: [88, 89] },
      watts: { data: [200, 210] },
      grade_smooth: { data: [0, 1] },
      moving: { data: [true, false] },
    });
    expect(s).toEqual({
      time: [0, 1],
      distance: [0, 3],
      altitude: [10, 11],
      velocitySmooth: [3, 3],
      heartrate: [140, 141],
      cadence: [88, 89],
      watts: [200, 210],
      gradeSmooth: [0, 1],
      moving: [true, false],
    });
  });

  it('omits missing or malformed streams and tolerates null input', () => {
    expect(mapStreams({ time: { data: 'nope' }, heartrate: {} })).toEqual({});
    expect(mapStreams(null as never)).toEqual({});
  });
});

describe('mapActivity', () => {
  it('normalizes a sparse Strava payload with defaults', () => {
    const a = mapActivity({ id: 123, type: 'Run', start_date: '2026-01-01T07:00:00Z' }, 'now');
    expect(a).toMatchObject({
      id: '123',
      source: 'strava',
      sportType: 'run',
      name: 'Untitled',
      distance: 0,
      movingTime: 0,
      hasHeartrate: false,
      trainer: false,
      manual: false,
      fetchedAt: 'now',
    });
  });

  it('prefers sport_type over the legacy type field', () => {
    const a = mapActivity({
      id: 1,
      type: 'Run',
      sport_type: 'TrailRun',
      start_date: '2026-01-01T07:00:00Z',
    });
    expect(a.sportType).toBe('trail_run');
  });
});

describe('Strava OAuth', () => {
  const config: StravaConfig = {
    clientId: '42',
    clientSecret: 'shh',
    redirectUri: 'http://localhost:8721/callback',
    scopes: 'read,activity:read_all',
    apiBase: 'https://www.strava.com/api/v3',
  };

  it('buildAuthorizeUrl encodes the read-only scopes, redirect and optional state', () => {
    const url = new URL(buildAuthorizeUrl(config, 'xyz'));
    expect(url.origin + url.pathname).toBe('https://www.strava.com/oauth/authorize');
    expect(url.searchParams.get('client_id')).toBe('42');
    expect(url.searchParams.get('redirect_uri')).toBe(config.redirectUri);
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('scope')).toBe('read,activity:read_all');
    expect(url.searchParams.get('state')).toBe('xyz');
    const noState = new URL(buildAuthorizeUrl({ ...config, clientId: undefined }));
    expect(noState.searchParams.has('state')).toBe(false);
    expect(noState.searchParams.get('client_id')).toBe('');
  });

  it('exchangeCodeForTokens posts the code and maps the token response', async () => {
    let body = '';
    let url = '';
    const fetchImpl = async (u: string, init?: RequestInit) => {
      url = u;
      body = String(init?.body ?? '');
      return Response.json({
        access_token: 'at',
        refresh_token: 'rt',
        expires_at: 1_800_000_000,
        scope: 'read',
        athlete: { id: 7 },
      });
    };
    const tokens = await exchangeCodeForTokens(config, 'the-code', fetchImpl);
    expect(url).toBe('https://www.strava.com/oauth/token');
    const params = new URLSearchParams(body);
    expect(params.get('code')).toBe('the-code');
    expect(params.get('grant_type')).toBe('authorization_code');
    expect(params.get('client_secret')).toBe('shh');
    expect(tokens).toEqual({
      accessToken: 'at',
      refreshToken: 'rt',
      expiresAt: 1_800_000_000,
      scope: 'read',
      athleteId: 7,
    });
  });

  it('exchangeCodeForTokens surfaces a rejected exchange with status and body', async () => {
    const fetchImpl = async () => new Response('bad code', { status: 400 });
    await expect(exchangeCodeForTokens(config, 'x', fetchImpl)).rejects.toThrow(
      /token request failed \(400\): bad code/,
    );
  });

  it('refreshTokens uses the refresh grant', async () => {
    let body = '';
    const fetchImpl = async (_u: string, init?: RequestInit) => {
      body = String(init?.body ?? '');
      return Response.json({ access_token: 'a2', refresh_token: 'r2', expires_at: 1 });
    };
    const t = await refreshTokens({ ...config, clientSecret: undefined }, 'r1', fetchImpl);
    const params = new URLSearchParams(body);
    expect(params.get('grant_type')).toBe('refresh_token');
    expect(params.get('refresh_token')).toBe('r1');
    expect(params.get('client_secret')).toBe('');
    expect(t.athleteId).toBeUndefined();
  });
});

// --- Logging (stderr only: MCP stdout is the wire) ------------------------------

describe('createLogger', () => {
  let stdout: ReturnType<typeof vi.spyOn>;
  let stderr: ReturnType<typeof vi.spyOn>;
  const env = { ...process.env };

  beforeEach(() => {
    stdout = vi.spyOn(process.stdout, 'write').mockImplementation(() => true);
    stderr = vi.spyOn(process.stderr, 'write').mockImplementation(() => true);
  });
  afterEach(() => {
    stdout.mockRestore();
    stderr.mockRestore();
    process.env.STRIDE_LOG = env.STRIDE_LOG;
    process.env.STRIDE_LOG_FORMAT = env.STRIDE_LOG_FORMAT;
    if (env.STRIDE_LOG === undefined) delete process.env.STRIDE_LOG;
    if (env.STRIDE_LOG_FORMAT === undefined) delete process.env.STRIDE_LOG_FORMAT;
  });

  const lines = (): string[] => stderr.mock.calls.map((c: unknown[]) => String(c[0]));

  it('defaults to warn: error/warn are written, info/debug are filtered', () => {
    delete process.env.STRIDE_LOG;
    delete process.env.STRIDE_LOG_FORMAT;
    const log = createLogger('t');
    log.error('e');
    log.warn('w');
    log.info('i');
    log.debug('d');
    expect(lines()).toEqual(['[error] t: e\n', '[warn] t: w\n']);
  });

  it('honours STRIDE_LOG levels (debug shows all, silent shows none, junk falls back)', () => {
    delete process.env.STRIDE_LOG_FORMAT;
    process.env.STRIDE_LOG = 'DEBUG';
    const log = createLogger();
    log.debug('d', { k: 1 });
    log.info('i', {});
    expect(lines()).toEqual(['[debug] d {"k":1}\n', '[info] i\n']);

    stderr.mockClear();
    process.env.STRIDE_LOG = 'silent';
    log.error('nope');
    expect(lines()).toEqual([]);

    process.env.STRIDE_LOG = 'loud';
    log.warn('fallback');
    log.info('hidden');
    expect(lines()).toEqual(['[warn] fallback\n']);
  });

  it('emits one JSON object per line in json format, with nested child scopes', () => {
    process.env.STRIDE_LOG = 'info';
    process.env.STRIDE_LOG_FORMAT = 'json';
    createLogger('api').child('sync').info('done', { n: 3 });
    logger.child('x').warn('root');
    const parsed = lines().map((l) => JSON.parse(l));
    expect(parsed[0]).toEqual({ level: 'info', scope: 'api.sync', msg: 'done', n: 3 });
    expect(parsed[1]).toEqual({ level: 'warn', scope: 'stride.x', msg: 'root' });
    expect(createLogger('').child('only')).toBeDefined();
  });

  it('never writes to stdout, at any level or format', () => {
    process.env.STRIDE_LOG = 'debug';
    for (const fmt of ['text', 'json']) {
      process.env.STRIDE_LOG_FORMAT = fmt;
      const log = createLogger('mcp');
      log.error('a');
      log.warn('b');
      log.info('c');
      log.debug('d');
      logLlmCall({ path: 'analyze', model: 'm', refused: false });
    }
    expect(stdout).not.toHaveBeenCalled();
    expect(stderr).toHaveBeenCalled();
  });

  it('logLlmCall logs info normally and warn for refusals/truncation, with usage fields', () => {
    process.env.STRIDE_LOG = 'info';
    process.env.STRIDE_LOG_FORMAT = 'json';
    logLlmCall({
      path: 'next',
      model: 'claude-x',
      requestId: 'req_1',
      usage: { inputTokens: 10, outputTokens: 2 },
      stopReason: 'end_turn',
      refused: false,
    });
    logLlmCall({ path: 'plan', model: 'm', stopReason: 'max_tokens', refused: false });
    logLlmCall({ path: 'plan', model: 'm', refused: true });
    const parsed = lines().map((l) => JSON.parse(l));
    expect(parsed[0]).toMatchObject({
      level: 'info',
      scope: 'coach.llm',
      msg: 'llm next',
      model: 'claude-x',
      requestId: 'req_1',
      inputTokens: 10,
    });
    expect(parsed[1].level).toBe('warn');
    expect(parsed[2]).toMatchObject({ level: 'warn', refused: true });
  });
});

describe('toLlmUsage', () => {
  it('normalizes snake_case usage and drops non-numbers', () => {
    expect(
      toLlmUsage({
        input_tokens: 5,
        output_tokens: 6,
        cache_read_input_tokens: 7,
        cache_creation_input_tokens: 'x',
      }),
    ).toEqual({
      inputTokens: 5,
      outputTokens: 6,
      cacheReadInputTokens: 7,
      cacheCreationInputTokens: undefined,
    });
  });

  it('returns undefined for missing or non-object usage', () => {
    expect(toLlmUsage(undefined)).toBeUndefined();
    expect(toLlmUsage(null)).toBeUndefined();
    expect(toLlmUsage(42)).toBeUndefined();
  });
});

// --- Coach tools ----------------------------------------------------------------

function demoContext(): CoachContext {
  return buildCoachContext({
    activities: [...demoHistory(), demoActivity()],
    profile: DEMO_PROFILE,
    goal: RaceGoal.parse({ distance: '10k', name: 'Autumn 10k', date: '2026-09-06' }),
    asOfDate: '2026-07-01',
  });
}

const provider: CoachDataProvider = {
  getContext: () => demoContext(),
  getZones: (): Zones => computeZones(DEMO_PROFILE),
};

describe('findCoachTool / runCoachTool', () => {
  it('finds every tool by name in frozen order and misses unknown names', () => {
    for (const t of COACH_TOOLS) expect(findCoachTool(t.name)).toBe(t);
    expect(findCoachTool('nope')).toBeUndefined();
  });

  it('runs each tool against a provider with a summary and data', async () => {
    for (const t of COACH_TOOLS) {
      const r = await runCoachTool(provider, t.name, {});
      expect(typeof r.summary).toBe('string');
      expect(r.data).toBeDefined();
    }
    const recent = await runCoachTool(provider, 'get_recent_activities', { limit: 2 });
    expect(recent.data).toHaveLength(2);
    const plan = (await runCoachTool(provider, 'get_plan_context')).data as { goal: RaceGoal };
    expect(plan.goal.name).toBe('Autumn 10k');
  });

  it('reports empty data clearly', async () => {
    const empty: CoachDataProvider = {
      getContext: () => ({
        generatedAt: 'x',
        profile: AthleteProfile.parse({}),
        recentActivities: [],
      }),
      getZones: () => ({ hr: [], pace: [] }),
    };
    expect((await runCoachTool(empty, 'get_training_load')).summary).toMatch(/No training-load/);
    expect((await runCoachTool(empty, 'get_plan_context')).summary).toMatch(/no goal/);
    const inputs = (await runCoachTool(empty, 'get_next_workout_inputs')).data as Record<
      string,
      unknown
    >;
    expect(inputs.tsb).toBeNull();
    expect(inputs.lastActivity).toBeNull();
  });

  it('rejects unknown tools and invalid input', async () => {
    await expect(runCoachTool(provider, 'nope')).rejects.toThrow(/Unknown coach tool/);
    await expect(
      runCoachTool(provider, 'get_recent_activities', { limit: 'many' }),
    ).rejects.toThrow();
  });
});

describe('CoachLLM.runTools (capturing mock — no network)', () => {
  it('exposes the shared toolset, honours tiering and logs the call', async () => {
    let params: any;
    const client: AnthropicLike = {
      messages: {
        create: async () => ({}),
        parse: async () => ({}),
        stream: () => ({ finalMessage: async () => ({}) }),
      },
      beta: {
        messages: {
          toolRunner: (p: any) => {
            params = p;
            return Promise.resolve({
              content: [{ type: 'text', text: 'ran tools' }],
              stop_reason: 'end_turn',
              usage: { input_tokens: 3, output_tokens: 4 },
              _request_id: 'req_tools',
            });
          },
        },
      },
    };
    const config = loadConfig({});
    const llm = createCoachLLM(config, client);
    const result = await llm?.runTools?.({
      model: config.models.chat,
      system: 'SYS',
      prompt: 'p',
      provider,
    });
    expect(result).toMatchObject({ text: 'ran tools', requestId: 'req_tools', refused: false });
    expect(params.tools.map((t: { name: string }) => t.name)).toEqual(
      COACH_TOOLS.map((t) => t.name),
    );
    expect(params.system[0].cache_control).toEqual({ type: 'ephemeral' });
    expect(params.output_config).toEqual({ effort: 'medium' });
    // Tool `run` returns the SAME data the shared tool computes.
    const out = await params.tools[0].run({});
    expect(JSON.parse(out)).toEqual((await runCoachTool(provider, COACH_TOOLS[0].name)).data);
  });

  it('discards text on refusal and uses the plan tier', async () => {
    let params: any;
    const client: AnthropicLike = {
      messages: {
        create: async () => ({}),
        parse: async () => ({}),
        stream: () => ({ finalMessage: async () => ({}) }),
      },
      beta: {
        messages: {
          toolRunner: (p: any) => {
            params = p;
            return Promise.resolve({
              content: [{ type: 'text', text: 'x' }],
              stop_reason: 'refusal',
            });
          },
        },
      },
    };
    const config = loadConfig({});
    const result = await createCoachLLM(config, client)?.runTools?.({
      model: config.models.plan,
      system: 'S',
      prompt: 'p',
      provider,
      maxTokens: 50,
    });
    expect(result).toMatchObject({ text: '', refused: true, stopReason: 'refusal' });
    expect(params.max_tokens).toBe(50);
    expect(params.output_config).toEqual({ effort: 'high' });
  });
});

// --- Prompt builders -------------------------------------------------------------

describe('prompt builders', () => {
  const ctx = demoContext();
  const activity = demoActivity();
  const metrics = computeActivityMetrics(activity, DEMO_PROFILE);

  it('formatContext lists the athlete, goal, load and recent activities', () => {
    const text = formatContext({
      ...ctx,
      planPhase: 'build',
      daysToRace: 40,
      profile: { ...ctx.profile, goals: 'sub-45' },
    });
    expect(text).toContain('Athlete: Demo Runner (intermediate, age 35).');
    expect(text).toContain('Stated goals: sub-45');
    expect(text).toContain('Goal race: Autumn 10k on 2026-09-06 (40 days away).');
    expect(text).toContain('Current training phase: build.');
    expect(text).toMatch(/CTL [\d.]+, ATL [\d.]+, TSB -?[\d.]+/);
    expect(text).toContain('Recent activities (most recent first):');
    expect(text.split('\n').filter((l) => l.startsWith('  - ')).length).toBeLessThanOrEqual(6);
  });

  it('formatContext is minimal for an empty context', () => {
    expect(
      formatContext({ generatedAt: 'x', profile: AthleteProfile.parse({}), recentActivities: [] }),
    ).toBe('Athlete: athlete (intermediate).');
  });

  it('formatMetrics renders the computed metrics', () => {
    const text = formatMetrics(activity, metrics);
    expect(text).toContain(`Activity: ${activity.name}`);
    expect(text).toContain(`${metrics.tss} TSS via ${metrics.method}`);
  });

  it('buildAnalyzePrompt embeds metrics and optional context', () => {
    const withCtx = buildAnalyzePrompt(activity, metrics, ctx);
    expect(withCtx).toContain('PRE-COMPUTED METRICS');
    expect(withCtx).toContain('TRAINING CONTEXT:');
    expect(buildAnalyzePrompt(activity, metrics)).not.toContain('TRAINING CONTEXT:');
  });

  it('buildNextPrompt quotes the code-selected session', () => {
    const p = buildNextPrompt(ctx, 'Easy run (45 min)');
    expect(p).toContain('"Easy run (45 min)"');
    expect(p).toContain('Do NOT change the prescription');
  });

  it('buildPlanProposalPrompt forbids numbers and states the week count', () => {
    const p = buildPlanProposalPrompt(ctx, 12, RaceGoal.parse({ distance: 'half' }));
    expect(p).toContain('12-week training plan');
    expect(p).toContain('numbered 1..12');
    expect(p).toContain('Output NO numbers');
    expect(p).toContain('(half)');
  });

  it('buildClassifyPrompt embeds the note', () => {
    expect(buildClassifyPrompt('knee hurts')).toContain('NOTE: knee hurts');
  });

  it('buildPlanSummaryPrompt outlines every week', () => {
    const plan = buildPlanSkeleton({
      profile: DEMO_PROFILE,
      goal: RaceGoal.parse({ distance: '10k' }),
      weeks: 3,
      startDate: '2026-07-06',
      planId: 'p',
      createdAt: 'x',
    });
    const p = buildPlanSummaryPrompt(plan, ctx);
    expect(p).toContain('3-week plan');
    expect(p).toContain('Week 1 (base)');
    expect(p).toContain('Week 3 (taper)');
  });
});
