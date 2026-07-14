import { readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { AthleteProfile } from '@stride/schemas';
import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_MODELS, type StrideConfig } from '../src/config';
import { buildPmcSeries, toDailyLoads } from '../src/science/pmc';
import { LocalStore } from '../src/store/index';
import { mapActivity } from '../src/strava/index';
import { type SyncParams, syncStrava } from '../src/sync';

// --- test scaffolding -------------------------------------------------------

const DAY = 86_400_000;

const dirs: string[] = [];
function tmpStore(): LocalStore {
  const dir = path.join(
    os.tmpdir(),
    `stride-sync-${process.pid}-${Math.random().toString(36).slice(2)}`,
  );
  dirs.push(dir);
  return new LocalStore(dir);
}
afterEach(async () => {
  for (const d of dirs.splice(0)) await new LocalStore(d).clear();
});

function cfg(): StrideConfig {
  return {
    strava: {
      clientId: '1',
      clientSecret: 'secret',
      redirectUri: 'http://localhost:8721/callback',
      scopes: 'read',
      apiBase: 'https://api.test/v3',
    },
    models: DEFAULT_MODELS,
    dataDir: '.unused',
    apiPort: 8720,
    webOrigin: 'http://localhost:5173',
  };
}

const PROFILE = AthleteProfile.parse({
  thresholdSpeedMps: 3.0,
  lthr: 160,
  maxHr: 190,
  restingHr: 50,
});

const RL_HEADERS = {
  'x-ratelimit-limit': '200,2000',
  'x-ratelimit-usage': '5,10',
  'x-readratelimit-limit': '100,1000',
  'x-readratelimit-usage': '5,10',
};

function jsonResponse(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

interface RawRun {
  id: number;
  name: string;
  sport_type: string;
  start_date: string;
  start_date_local: string;
  distance: number;
  moving_time: number;
  elapsed_time: number;
  total_elevation_gain: number;
  average_speed: number;
  average_heartrate: number;
  has_heartrate: boolean;
}

function rawRun(id: number, startMs: number, speed = 2.8): RawRun {
  const iso = new Date(startMs).toISOString();
  const km = 8;
  const distance = km * 1000;
  const movingTime = Math.round(distance / speed);
  return {
    id,
    name: 'Run',
    sport_type: 'Run',
    start_date: iso,
    start_date_local: iso.replace('Z', ''),
    distance,
    moving_time: movingTime,
    elapsed_time: movingTime,
    total_elevation_gain: 20,
    average_speed: speed,
    average_heartrate: 150,
    has_heartrate: true,
  };
}

/** A run every `stepDays` days, `count` of them, ending `endMs`. */
function history(endMs: number, count: number, stepDays = 2): RawRun[] {
  return Array.from({ length: count }, (_, i) =>
    rawRun(1000 + i, endMs - (count - 1 - i) * stepDays * DAY),
  );
}

interface MockOptions {
  raw: RawRun[];
  /** Return 429 for activity pages with page number greater than this. */
  failPageAfter?: number;
  retryAfter?: string;
}

function mockStrava(opts: MockOptions) {
  const calls = { activityQueries: [] as URLSearchParams[], streams: 0 };
  const fetchImpl = async (url: string): Promise<Response> => {
    const u = new URL(url);
    if (u.pathname.endsWith('/athlete/activities')) {
      const q = u.searchParams;
      calls.activityQueries.push(q);
      const page = Number(q.get('page') ?? '1');
      const perPage = Number(q.get('per_page') ?? '200');
      const before = q.get('before') ? Number(q.get('before')) : undefined;
      const after = q.get('after') ? Number(q.get('after')) : undefined;
      if (opts.failPageAfter !== undefined && page > opts.failPageAfter) {
        return jsonResponse(
          { message: 'Rate Limit Exceeded' },
          429,
          opts.retryAfter ? { 'retry-after': opts.retryAfter } : {},
        );
      }
      const filtered = opts.raw
        .filter((a) => {
          const t = Math.floor(Date.parse(a.start_date) / 1000);
          if (before !== undefined && !(t < before)) return false;
          if (after !== undefined && !(t > after)) return false;
          return true;
        })
        .sort((a, b) => b.start_date.localeCompare(a.start_date)); // newest first
      const slice = filtered.slice((page - 1) * perPage, (page - 1) * perPage + perPage);
      return jsonResponse(slice, 200, RL_HEADERS);
    }
    if (u.pathname.includes('/streams')) {
      calls.streams++;
      return jsonResponse({}, 200, RL_HEADERS);
    }
    if (u.pathname.endsWith('/athlete')) return jsonResponse({ id: 42 }, 200, RL_HEADERS);
    return jsonResponse({}, 200, RL_HEADERS);
  };
  return { fetchImpl, calls };
}

async function seededStore(): Promise<LocalStore> {
  const store = tmpStore();
  await store.saveTokens({
    accessToken: 'a',
    refreshToken: 'r',
    expiresAt: 9_999_999_999,
    athleteId: 42,
  });
  await store.saveProfile(PROFILE);
  return store;
}

function baseParams(store: LocalStore, fetchImpl: SyncParams['fetchImpl']): SyncParams {
  return { store, config: cfg(), fetchImpl, sleep: async () => {} };
}

// --- tests ------------------------------------------------------------------

describe('syncStrava — prune-but-PMC-survives (headline invariant)', () => {
  it('prunes raw at 7 days but keeps the durable PMC intact', async () => {
    const store = await seededStore();
    const T0 = Date.parse('2026-07-08T00:00:00Z');
    // Backfill: 15 runs over ~40 days, all fetched now (fetchedAt = T0).
    const raw = history(Date.parse('2026-07-01T06:00:00Z'), 15, 3);
    const { fetchImpl } = mockStrava({ raw });

    const r1 = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });
    expect(r1.mode).toBe('backfill');
    expect(r1.fetched).toBe(15);

    const dailyBefore = await store.loadDailyLoads();
    expect(dailyBefore.length).toBeGreaterThan(0);
    const pmcBefore = buildPmcSeries(dailyBefore);
    expect((await store.loadActivities()).length).toBe(15);

    // 8 days later, nothing new. Raw expires; the durable series must not.
    const T1 = T0 + 8 * DAY;
    const r2 = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T1 });
    expect(r2.mode).toBe('incremental');

    expect(await store.loadActivities()).toHaveLength(0); // raw pruned
    const dailyAfter = await store.loadDailyLoads();
    // The daily-load aggregate (tss per date) is unchanged, so the recomputed
    // PMC is byte-for-byte the pre-prune series. This is the compliance +
    // correctness proof: §4 raw cache expires, §7 derived series persists.
    expect(buildPmcSeries(dailyAfter)).toEqual(pmcBefore);
    expect(pmcBefore.length).toBeGreaterThan(30);
  });
});

describe('syncStrava — backfill paging', () => {
  it('pages through 450 activities (200/page) and marks backfill complete', async () => {
    const store = await seededStore();
    const raw = history(Date.parse('2026-07-01T06:00:00Z'), 450, 1);
    const { fetchImpl, calls } = mockStrava({ raw });
    const T0 = Date.parse('2026-07-02T00:00:00Z');

    const r = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });
    expect(r.mode).toBe('backfill');
    expect(r.fetched).toBe(450);
    expect(calls.activityQueries.length).toBe(3); // 200 + 200 + 50 (short page)

    const state = await store.loadSyncState();
    expect(state?.backfillComplete).toBe(true);
    expect(state?.backfillCursor).toBeUndefined();
    expect(state?.athleteId).toBe(42);
  });
});

describe('syncStrava — incremental watermark', () => {
  it('sends `after` and fetches only new activities after backfill', async () => {
    const store = await seededStore();
    const T0 = Date.parse('2026-07-08T00:00:00Z');
    // History ends 2 days before T0, so nothing is inside the 24h overlap.
    const raw = history(Date.parse('2026-07-06T06:00:00Z'), 6, 2);
    const { fetchImpl, calls } = mockStrava({ raw });

    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 }); // backfill
    calls.activityQueries.length = 0;

    // A brand-new run lands at T0; incremental should fetch just that one.
    raw.push(rawRun(9999, T0));
    const r = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 + 3_600_000 });

    expect(r.mode).toBe('incremental');
    expect(calls.activityQueries.some((q) => q.has('after'))).toBe(true);
    expect(r.fetched).toBe(1);
  });
});

describe('syncStrava — rate-limit degrade', () => {
  it('saves partial results, does not throw, and records a resume cursor', async () => {
    const store = await seededStore();
    const raw = history(Date.parse('2026-07-01T06:00:00Z'), 450, 1);
    // Page 1 succeeds, page 2+ persistently 429s.
    const { fetchImpl } = mockStrava({ raw, failPageAfter: 1, retryAfter: '1' });
    const T0 = Date.parse('2026-07-02T00:00:00Z');
    let rateLimitHits = 0;

    const r = await syncStrava({
      ...baseParams(store, fetchImpl),
      nowMs: T0,
      onRateLimit: () => {
        rateLimitHits++;
      },
    });

    expect(r.mode).toBe('backfill');
    expect(r.fetched).toBe(200); // only page 1 survived
    expect(rateLimitHits).toBeGreaterThan(0);
    expect((await store.loadActivities()).length).toBe(200); // partial saved

    const state = await store.loadSyncState();
    expect(state?.backfillComplete).toBe(false);
    expect(state?.backfillCursor).toBeDefined();
  });
});

describe('syncStrava — deletion reconciliation', () => {
  it('removes locally stored activities deleted upstream (opt-in --reconcile)', async () => {
    const store = await seededStore();
    const T0 = Date.parse('2026-07-08T00:00:00Z');
    // Three recent runs (within the incremental overlap window).
    const a = rawRun(1, T0 - 10 * 3_600_000);
    const b = rawRun(2, T0 - 6 * 3_600_000);
    const c = rawRun(3, T0 - 2 * 3_600_000);
    const raw = [a, b, c];
    const { fetchImpl } = mockStrava({ raw });

    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 }); // backfill: 3 stored
    expect((await store.loadActivities()).length).toBe(3);

    // b is deleted on Strava.
    raw.splice(1, 1);
    const r = await syncStrava({
      ...baseParams(store, fetchImpl),
      nowMs: T0 + 3_600_000,
      reconcile: true,
    });

    expect(r.deleted).toBe(1);
    expect((await store.loadActivities()).map((x) => x.id).sort()).toEqual(['1', '3']);
    const state = await store.loadSyncState();
    expect(state?.lastReconcileAt).toBeDefined();
  });
});

describe('syncStrava — reconciliation keeps the durable series authoritative', () => {
  // Two runs on the SAME day (D1) + one on another day (D2), plus older/newer
  // guard runs so the reconciliation window brackets the deleted activities.
  const T0 = Date.parse('2026-07-02T12:00:00Z');
  const gOld = () => rawRun(10, Date.parse('2026-06-20T06:00:00Z'));
  const a2 = () => rawRun(21, Date.parse('2026-06-25T05:00:00Z')); // D1 (earlier)
  const a1 = () => rawRun(22, Date.parse('2026-06-25T07:00:00Z')); // D1 (later)
  const lone = () => rawRun(30, Date.parse('2026-06-27T06:00:00Z')); // D2 (sole activity)
  const gNew = () => rawRun(40, Date.parse('2026-07-01T06:00:00Z'));
  const D1 = '2026-06-25';
  const D2 = '2026-06-27';

  it('drops a same-day pair to the survivor, and removes a fully-emptied day', async () => {
    const store = await seededStore();
    const raw = [gOld(), a2(), a1(), lone(), gNew()];
    const { fetchImpl } = mockStrava({ raw });

    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 }); // backfill: 5 stored
    const before = await store.loadDailyLoads();
    const d1Before = before.find((d) => d.date === D1)?.tss ?? 0;
    const singleLoad = toDailyLoads([mapActivity(a1(), new Date(T0).toISOString())], PROFILE)[0]
      .tss;
    expect(d1Before).toBeCloseTo(singleLoad * 2, 5); // a1 + a2

    // (A) One of the same-day pair is deleted upstream. Reconcile.
    raw.splice(
      raw.findIndex((r) => r.id === 21),
      1,
    );
    const rA = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0, backfill: true });
    expect(rA.deleted).toBe(1);
    const afterA = await store.loadDailyLoads();
    const d1After = afterA.find((d) => d.date === D1)?.tss ?? 0;
    expect(d1After).toBeCloseTo(singleLoad, 5); // dropped to the survivor's load
    expect(d1After).toBeLessThan(d1Before);
    expect(afterA.find((d) => d.date === D2)).toBeDefined(); // untouched day still there

    // (B) The ONLY activity on D2 is deleted upstream. Reconcile.
    raw.splice(
      raw.findIndex((r) => r.id === 30),
      1,
    );
    const rB = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0, backfill: true });
    expect(rB.deleted).toBe(1);
    const afterB = await store.loadDailyLoads();
    // The date is REMOVED (not left as stale phantom load).
    expect(afterB.find((d) => d.date === D2)).toBeUndefined();

    // The durable PMC now matches a PMC recomputed straight from retained raw —
    // i.e. no phantom load lingers anywhere in the series.
    const durablePmc = buildPmcSeries(afterB);
    const rawPmc = buildPmcSeries(toDailyLoads(await store.loadActivities(), PROFILE));
    expect(durablePmc).toEqual(rawPmc);
  });
});

describe('syncStrava — idempotency', () => {
  it('two identical syncs produce a byte-identical daily-loads.json', async () => {
    const store = await seededStore();
    const raw = history(Date.parse('2026-07-01T06:00:00Z'), 12, 2);
    const { fetchImpl } = mockStrava({ raw });
    const T0 = Date.parse('2026-07-02T00:00:00Z');

    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });
    const first = await readFile(path.join(store.dir, 'daily-loads.json'), 'utf8');
    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });
    const second = await readFile(path.join(store.dir, 'daily-loads.json'), 'utf8');

    expect(second).toBe(first);
  });
});

describe('syncStrava — migration seed (existing user upgrade)', () => {
  it('seeds the durable series from existing raw BEFORE the first prune', async () => {
    const store = await seededStore();
    const T0 = Date.parse('2026-07-20T00:00:00Z');
    // Existing user: raw activities present but NO durable series yet, and the
    // raw was fetched 10 days ago (so it will be pruned on this sync).
    const staleFetched = new Date(T0 - 10 * DAY).toISOString();
    const existing = history(Date.parse('2026-07-08T06:00:00Z'), 5, 2).map((r) =>
      mapActivity(r, staleFetched),
    );
    await store.saveActivities(existing);
    expect(await store.loadDailyLoads()).toEqual([]);

    // Nothing new to fetch.
    const { fetchImpl } = mockStrava({ raw: [] });
    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });

    // Raw is pruned (stale), but the durable series was seeded first, so the
    // PMC never collapses to zero on upgrade.
    expect(await store.loadActivities()).toHaveLength(0);
    const daily = await store.loadDailyLoads();
    expect(daily.length).toBe(5);
    expect(buildPmcSeries(daily).length).toBeGreaterThan(0);
  });
});

describe('syncStrava — rebuild', () => {
  it('re-downloads and replaces the durable series wholesale', async () => {
    const store = await seededStore();
    const raw = history(Date.parse('2026-07-01T06:00:00Z'), 8, 2);
    const { fetchImpl } = mockStrava({ raw });
    const T0 = Date.parse('2026-07-02T00:00:00Z');

    await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0 });
    const r = await syncStrava({ ...baseParams(store, fetchImpl), nowMs: T0, rebuild: true });

    expect(r.mode).toBe('rebuild');
    expect((await store.loadDailyLoads()).length).toBe(8);
    const state = await store.loadSyncState();
    expect(state?.backfillComplete).toBe(true);
  });
});
