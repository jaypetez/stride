import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  LocalStore,
  loadConfig,
  StravaApiError,
  StravaRateLimitError,
  SyncLockError,
  toDailyLoads,
} from '@stride/core';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildApp } from '../src/app';

/**
 * AUD-013: every route in `app.ts` has at least one success and one failure
 * test, every `onError` mapping is asserted, and a route-enumeration test fails
 * when a route is added without registering tests for it in `ROUTE_TESTS`.
 */

// `syncStrava` needs Strava credentials + network; stub it per test so /sync's
// success path and every error mapping can be driven offline. A plain function
// (not vi.fn) so a stubbed throw is an ordinary rejection.
const sync = vi.hoisted(() => ({ impl: undefined as undefined | (() => Promise<unknown>) }));
vi.mock('@stride/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@stride/core')>();
  return {
    ...real,
    syncStrava: (...args: Parameters<typeof real.syncStrava>) =>
      sync.impl ? sync.impl() : real.syncStrava(...args),
  };
});

const dirs: string[] = [];
afterAll(async () => {
  await Promise.all(dirs.map((d) => rm(d, { recursive: true, force: true })));
});
beforeEach(() => {
  sync.impl = undefined;
});

async function tempStore(): Promise<LocalStore> {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'stride-api-routes-'));
  dirs.push(dir);
  return new LocalStore(dir);
}

const config = loadConfig({ STRIDE_NOW: '2026-07-09T00:00:00Z' });

async function emptyApp() {
  return buildApp({ config, store: await tempStore(), llm: null });
}

/** A live store seeded with the demo athlete (activities WITH streams). */
async function seededApp() {
  const store = await tempStore();
  const activities = [...demoHistory(), demoActivity()];
  await store.saveProfile({ ...DEMO_PROFILE, name: 'Live Runner' });
  await store.saveActivities(activities);
  await store.saveDailyLoads(toDailyLoads(activities, DEMO_PROFILE));
  return { app: buildApp({ config, store, llm: null }), store };
}

/** An app whose store throws `err` from every read, to drive `onError`. */
async function failingApp(err: Error) {
  const store = await tempStore();
  const boom = async () => {
    throw err;
  };
  Object.assign(store, {
    loadProfile: boom,
    loadActivities: boom,
    loadDailyLoads: boom,
    loadGoal: boom,
    loadPlan: boom,
  });
  return buildApp({ config, store, llm: null });
}

const json = (body: unknown) => ({
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

/** Assert the standard `{ error, requestId }` envelope + matching header. */
async function expectEnvelope(res: Response, status: number, error?: RegExp) {
  expect(res.status).toBe(status);
  const body = (await res.json()) as { error: string; requestId: string };
  expect(typeof body.error).toBe('string');
  if (error) expect(body.error).toMatch(error);
  expect(typeof body.requestId).toBe('string');
  expect(res.headers.get('x-request-id')).toBe(body.requestId);
  return body;
}

type Case = () => Promise<void>;

/**
 * The route test registry. Keyed by `METHOD path` exactly as Hono registers it;
 * each route lists its success and failure cases. Adding a route to `app.ts`
 * without an entry here fails the enumeration test below.
 */
const ROUTE_TESTS: Record<
  string,
  { success: Record<string, Case>; failure: Record<string, Case> }
> = {
  'GET /health': {
    success: {
      'returns ok + version': async () => {
        const res = await (await emptyApp()).request('/health');
        expect(res.status).toBe(200);
        expect(await res.json()).toMatchObject({ status: 'ok', version: expect.any(String) });
      },
    },
    failure: {
      'an unsupported method is a 404 envelope': async () => {
        const res = await (await emptyApp()).request('/health', {
          method: 'DELETE',
          headers: { 'content-type': 'application/json' },
        });
        await expectEnvelope(res, 404, /No route for DELETE \/health/);
      },
    },
  },

  'GET /profile': {
    success: {
      'returns a default profile for an empty store': async () => {
        const res = await (await emptyApp()).request('/profile');
        expect(res.status).toBe(200);
        expect(await res.json()).toMatchObject({ id: 'me', medicalClearance: false });
      },
      'returns the stored profile': async () => {
        const { app } = await seededApp();
        expect(((await (await app.request('/profile')).json()) as any).name).toBe('Live Runner');
      },
    },
    failure: {
      'a store failure is a 500 envelope': async () => {
        const res = await (await failingApp(new Error('disk on fire'))).request('/profile');
        // The envelope carries a fixed message; internal details stay in the log.
        const body = await expectEnvelope(res, 500, /^Internal Server Error$/);
        expect(body.error).not.toMatch(/disk on fire/);
      },
    },
  },

  'POST /profile/screening': {
    success: {
      'all-no answers clear the athlete': async () => {
        const app = await emptyApp();
        const res = await app.request(
          '/profile/screening',
          json({ answers: Array(7).fill(false) }),
        );
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.screening.cleared).toBe(true);
        expect(body.profile.medicalClearance).toBe(true);
        expect(body.profile.updatedAt).toBe('2026-07-09T00:00:00Z');
      },
    },
    failure: {
      'malformed PAR-Q arrays are 400 envelopes': async () => {
        const app = await emptyApp();
        for (const bad of [
          {},
          { answers: 'yes' },
          { answers: [true, 'no'] },
          { answers: [1, 0] },
          { answers: null },
          { answers: [[true]] },
        ]) {
          const res = await app.request('/profile/screening', json(bad));
          await expectEnvelope(res, 400, /Invalid request — answers/);
        }
      },
      'a malformed JSON body is a 400 envelope (not a 500)': async () => {
        const res = await (await emptyApp()).request('/profile/screening', json('{"answers": ['));
        await expectEnvelope(res, 400, /Malformed JSON/);
      },
    },
  },

  'GET /activities': {
    success: {
      'demo: newest first, honours limit and strips streams': async () => {
        const res = await (await emptyApp()).request('/activities?demo=true&limit=3');
        expect(res.status).toBe(200);
        const body = (await res.json()) as any[];
        expect(body).toHaveLength(3);
        expect(body[0].id).toBe('demo-activity');
        const dates = body.map((a) => a.startDate);
        expect([...dates].sort().reverse()).toEqual(dates);
        for (const a of body) expect(a).not.toHaveProperty('streams');
      },
      'live: strips stored streams; bad limits fall back to 50': async () => {
        const { app } = await seededApp();
        const all = [...demoHistory(), demoActivity()].length;
        for (const limit of ['abc', '0', '-5', '']) {
          const body = (await (await app.request(`/activities?limit=${limit}`)).json()) as any[];
          expect(body, `limit=${limit}`).toHaveLength(Math.min(all, 50));
        }
        const top = (await (await app.request('/activities?limit=1')).json()) as any[];
        expect(top).toHaveLength(1);
        expect(top[0].id).toBe('demo-activity');
        expect(top[0]).not.toHaveProperty('streams');
      },
      'live: an empty store returns []': async () => {
        expect(await (await (await emptyApp()).request('/activities')).json()).toEqual([]);
      },
    },
    failure: {
      'a repeated query param is a 400 envelope': async () => {
        const res = await (await emptyApp()).request('/activities?limit=1&limit=2');
        await expectEnvelope(res, 400, /limit/);
      },
      'a Strava API error is a 502 envelope': async () => {
        const res = await (await failingApp(new StravaApiError(503, 'Strava down'))).request(
          '/activities',
        );
        await expectEnvelope(res, 502, /^Upstream Strava request failed$/);
      },
    },
  },

  'GET /pmc': {
    success: {
      'demo: PMC + ACWR series with latest points': async () => {
        const res = await (await emptyApp()).request('/pmc?demo=true');
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.pmc.length).toBeGreaterThan(0);
        expect(body.acwr.length).toBeGreaterThan(0);
        expect(body.latest).toEqual(body.pmc.at(-1));
        expect(typeof body.rampRatePerWeek).toBe('number');
      },
      'live: reads the durable daily loads (empty => nulls)': async () => {
        const { app } = await seededApp();
        expect(((await (await app.request('/pmc')).json()) as any).latest).not.toBeNull();
        const empty = (await (await (await emptyApp()).request('/pmc')).json()) as any;
        expect(empty).toMatchObject({ pmc: [], acwr: [], latest: null, latestAcwr: null });
      },
    },
    failure: {
      'a sync-lock error is a 409 envelope': async () => {
        const res = await (await failingApp(new SyncLockError())).request('/pmc');
        await expectEnvelope(res, 409, /already in progress/);
      },
      'a repeated demo param is a 400 envelope': async () => {
        const res = await (await emptyApp()).request('/pmc?demo=true&demo=false');
        await expectEnvelope(res, 400, /demo/);
      },
    },
  },

  'GET /analyze/:id': {
    success: {
      'demo: metrics + analysis': async () => {
        const res = await (await emptyApp()).request('/analyze/demo');
        expect(res.status).toBe(200);
        expect(((await res.json()) as any).metrics.tss).toBeGreaterThan(0);
      },
      'live: by id and by "last"': async () => {
        const { app } = await seededApp();
        const byId = (await (await app.request('/analyze/demo-hist-0')).json()) as any;
        expect(byId.metrics.activityId).toBe('demo-hist-0');
        const last = (await (await app.request('/analyze/last')).json()) as any;
        expect(last.metrics.activityId).toBe('demo-activity');
      },
    },
    failure: {
      'an unknown id is a 404 envelope': async () => {
        const { app } = await seededApp();
        await expectEnvelope(await app.request('/analyze/nope'), 404, /Activity "nope" not found/);
      },
      '"last" on an empty store is a 404 envelope': async () => {
        await expectEnvelope(await (await emptyApp()).request('/analyze/last'), 404, /not found/);
      },
    },
  },

  'GET /next': {
    success: {
      'demo and live both return a workout with context': async () => {
        const demo = (await (await (await emptyApp()).request('/next?demo=true')).json()) as any;
        expect(demo.workout.type).toBeDefined();
        const { app } = await seededApp();
        const live = (await (await app.request('/next?note=feeling%20good')).json()) as any;
        expect(live.workout.type).toBeDefined();
        expect(live.context.fitness).toBeDefined();
        expect(typeof live.disclaimer).toBe('string');
      },
    },
    failure: {
      'a Strava rate limit is a 429 envelope': async () => {
        const res = await (await failingApp(new StravaRateLimitError(undefined))).request('/next');
        await expectEnvelope(res, 429, /rate limit/);
      },
    },
  },

  'POST /plan': {
    success: {
      'demo: returns a valid plan and persists nothing': async () => {
        const app = await emptyApp();
        const res = await app.request('/plan', json({ demo: true, race: '5k', weeks: 4 }));
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.plan.weeks).toHaveLength(4);
        expect(body.validation.valid).toBe(true);
        expect((await app.request('/plan')).status).toBe(404);
      },
      'live: persists the plan and goal (defaults to 8 weeks from now)': async () => {
        const { app, store } = await seededApp();
        const res = await app.request('/plan', json({ race: 'half', date: '2026-10-04' }));
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.plan.weeks).toHaveLength(8);
        // The 8 weeks are aligned to end on the goal race (AUD-003).
        expect(body.plan.startDate).toBe('2026-08-10');
        expect(body.plan.endDate).toBe('2026-10-04');
        expect((await store.loadPlan())?.id).toBe(body.plan.id);
        expect(await store.loadGoal()).toMatchObject({ distance: 'half', date: '2026-10-04' });
      },
      'live build: persists the plan but never overwrites the race goal (MVP-026)': async () => {
        const { app, store } = await seededApp();
        await app.request('/plan', json({ race: 'half', date: '2026-10-04' }));
        const res = await app.request('/plan', json({ kind: 'build', weeks: 6 }));
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.plan.kind).toBe('build');
        expect((await store.loadPlan())?.id).toBe(body.plan.id);
        // The race goal from the earlier race plan is left untouched.
        expect(await store.loadGoal()).toMatchObject({ distance: 'half', date: '2026-10-04' });
      },
      'live with no recent runs: omits the seed and falls back to the template': async () => {
        const app = await emptyApp();
        const res = await app.request('/plan', json({ kind: 'build', weeks: 4 }));
        expect(res.status).toBe(200);
        const body = (await res.json()) as any;
        expect(body.plan.kind).toBe('build');
        expect(body.seed).toBeUndefined();
      },
    },
    failure: {
      'invalid bodies are 400 envelopes': async () => {
        const app = await emptyApp();
        for (const bad of [{ weeks: 'x' }, { weeks: 0 }, { weeks: 53 }, { race: 'ultra' }]) {
          await expectEnvelope(await app.request('/plan', json(bad)), 400, /Invalid request/);
        }
      },
    },
  },

  'GET /plan': {
    success: {
      'returns the saved plan after POST /plan': async () => {
        const { app } = await seededApp();
        const posted = (await (await app.request('/plan', json({ weeks: 3 }))).json()) as any;
        const res = await app.request('/plan');
        expect(res.status).toBe(200);
        expect(((await res.json()) as any).id).toBe(posted.plan.id);
      },
    },
    failure: {
      'no saved plan is a 404 envelope': async () => {
        await expectEnvelope(await (await emptyApp()).request('/plan'), 404, /No saved plan/);
      },
    },
  },

  'POST /sync': {
    success: {
      'returns the sync result': async () => {
        sync.impl = async () => ({ fetched: 3, ok: true });
        const res = await (await emptyApp()).request('/sync', json({}));
        expect(res.status).toBe(200);
        expect(await res.json()).toEqual({ fetched: 3, ok: true });
      },
    },
    failure: {
      'offline (no credentials) is a 500 envelope': async () => {
        const res = await (await emptyApp()).request('/sync', json({}));
        await expectEnvelope(res, 500);
      },
    },
  },
};

describe('API routes (registry)', () => {
  for (const [route, { success, failure }] of Object.entries(ROUTE_TESTS)) {
    describe(route, () => {
      for (const [name, run] of Object.entries(success)) it(`success: ${name}`, run);
      for (const [name, run] of Object.entries(failure)) it(`failure: ${name}`, run);
    });
  }

  it('every registered route has success AND failure tests (and no stale entries)', async () => {
    const app = await emptyApp();
    const registered = [
      ...new Set(app.routes.filter((r) => r.method !== 'ALL').map((r) => `${r.method} ${r.path}`)),
    ].sort();
    expect(registered.length).toBeGreaterThan(0);
    expect(Object.keys(ROUTE_TESTS).sort()).toEqual(registered);
    for (const [route, cases] of Object.entries(ROUTE_TESTS)) {
      expect(Object.keys(cases.success).length, `${route} success`).toBeGreaterThan(0);
      expect(Object.keys(cases.failure).length, `${route} failure`).toBeGreaterThan(0);
    }
  });
});

describe('onError status mapping', () => {
  const cases: Array<[string, () => Error, number, RegExp]> = [
    ['StravaRateLimitError', () => new StravaRateLimitError(undefined), 429, /rate limit/],
    ['StravaApiError', () => new StravaApiError(500, 'upstream exploded'), 502, /^Upstream Strava/],
    ['SyncLockError', () => new SyncLockError('locked'), 409, /locked/],
    ['a generic Error', () => new Error('kaboom'), 500, /^Internal Server Error$/],
    ['an Error with no message', () => new Error(''), 500, /Internal Server Error/],
  ];

  for (const [name, make, status, message] of cases) {
    it(`maps ${name} from /sync to ${status}`, async () => {
      sync.impl = async () => {
        throw make();
      };
      const res = await (await emptyApp()).request('/sync', {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-request-id': `req-${status}` },
      });
      const body = await expectEnvelope(res, status, message);
      // A caller-supplied request id is propagated end to end.
      expect(body.requestId).toBe(`req-${status}`);
    });
  }

  it('maps a framework 400 (malformed JSON) to 400', async () => {
    const res = await (await emptyApp()).request('/plan', json('not json'));
    await expectEnvelope(res, 400, /Malformed JSON/);
  });

  it('maps an unknown route to a 404 envelope', async () => {
    await expectEnvelope(await (await emptyApp()).request('/nope'), 404, /No route for GET \/nope/);
  });

  it('a validation failure (400) goes through the envelope', async () => {
    await expectEnvelope(
      await (await emptyApp()).request('/profile/screening', json({ answers: 'x' })),
      400,
    );
  });
});
