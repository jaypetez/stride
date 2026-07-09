import os from 'node:os';
import path from 'node:path';
import type { Activity } from '@stride/schemas';
import { afterAll, describe, expect, it } from 'vitest';
import type { StravaConfig } from '../src/config';
import { demoActivity } from '../src/fixtures';
import { LocalStore } from '../src/store/index';
import { mapActivity, StravaClient, StravaRateLimitError } from '../src/strava/index';

function jsonResponse(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

const config: StravaConfig = {
  clientId: '1',
  clientSecret: 'secret',
  redirectUri: 'http://localhost:8721/callback',
  scopes: 'read,activity:read_all',
  apiBase: 'https://api.test/v3',
};

describe('mapper', () => {
  it('maps a Strava summary to a normalized Activity', () => {
    const a = mapActivity({
      id: 999,
      name: 'Lunch Run',
      sport_type: 'Run',
      start_date: '2026-07-01T11:00:00Z',
      start_date_local: '2026-07-01T13:00:00',
      distance: 8000,
      moving_time: 2400,
      elapsed_time: 2500,
      total_elevation_gain: 40,
      average_speed: 3.33,
      has_heartrate: true,
      average_heartrate: 150,
    });
    expect(a.id).toBe('999');
    expect(a.source).toBe('strava');
    expect(a.sportType).toBe('run');
    expect(a.hasHeartrate).toBe(true);
  });
});

describe('StravaClient', () => {
  it('refreshes an expired token, persists the rotated token, and tracks rate limits', async () => {
    let refreshed = false;
    let lastAuth: string | null = null;
    let persistedRefresh: string | undefined;
    const fetchImpl = async (url: string, init?: RequestInit) => {
      if (url.includes('/oauth/token')) {
        refreshed = true;
        return jsonResponse({
          access_token: 'new',
          refresh_token: 'r2',
          expires_at: 9_999_999_999,
        });
      }
      lastAuth = (init?.headers as Record<string, string>)?.Authorization ?? null;
      return jsonResponse({ id: 42 }, 200, {
        'x-ratelimit-limit': '200,2000',
        'x-ratelimit-usage': '10,50',
      });
    };
    const client = new StravaClient({
      config,
      tokens: { accessToken: 'old', refreshToken: 'r1', expiresAt: 0 },
      fetchImpl,
      onTokensRefreshed: (t) => {
        persistedRefresh = t.refreshToken;
      },
      now: () => 1_000_000_000,
    });
    const athlete = await client.getAthlete();
    expect(refreshed).toBe(true);
    expect(athlete.id).toBe(42);
    expect(lastAuth).toBe('Bearer new');
    expect(client.getTokens().accessToken).toBe('new');
    expect(persistedRefresh).toBe('r2');
    expect(client.getRateLimitStatus()?.shortUsage).toBe(10);
  });

  it('throws StravaRateLimitError on HTTP 429', async () => {
    const client = new StravaClient({
      config,
      tokens: { accessToken: 'a', refreshToken: 'r', expiresAt: 9_999_999_999 },
      fetchImpl: async () => jsonResponse({ message: 'Rate Limit Exceeded' }, 429),
      now: () => 1000,
    });
    await expect(client.getActivitiesPage()).rejects.toBeInstanceOf(StravaRateLimitError);
  });

  it('maps a page of activities', async () => {
    const client = new StravaClient({
      config,
      tokens: { accessToken: 'a', refreshToken: 'r', expiresAt: 9_999_999_999 },
      fetchImpl: async () =>
        jsonResponse([
          {
            id: 1,
            name: 'A',
            sport_type: 'Run',
            start_date: '2026-07-01T06:00:00Z',
            distance: 5000,
            moving_time: 1500,
            elapsed_time: 1500,
          },
          {
            id: 2,
            name: 'B',
            sport_type: 'TrailRun',
            start_date: '2026-07-02T06:00:00Z',
            distance: 9000,
            moving_time: 3000,
            elapsed_time: 3100,
          },
        ]),
      now: () => 1000,
    });
    const acts = await client.getActivitiesPage({ fetchedAt: '2026-07-09T00:00:00Z' });
    expect(acts).toHaveLength(2);
    expect(acts[1].sportType).toBe('trail_run');
    expect(acts[0].fetchedAt).toBe('2026-07-09T00:00:00Z');
  });
});

describe('LocalStore', () => {
  const dir = path.join(os.tmpdir(), `stride-test-${process.pid}-${Date.now()}`);
  const store = new LocalStore(dir);

  afterAll(async () => {
    await store.clear();
  });

  it('round-trips tokens and activities, and enforces 7-day Strava expiry', async () => {
    await store.saveTokens({ accessToken: 'a', refreshToken: 'b', expiresAt: 123 });
    expect((await store.loadTokens())?.refreshToken).toBe('b');

    const now = Date.parse('2026-07-09T00:00:00Z');
    const fresh: Activity = {
      ...demoActivity(),
      id: 'fresh',
      source: 'strava',
      fetchedAt: '2026-07-08T00:00:00Z',
    };
    const stale: Activity = {
      ...demoActivity(),
      id: 'stale',
      source: 'strava',
      fetchedAt: '2026-06-01T00:00:00Z',
    };
    const manual: Activity = { ...demoActivity(), id: 'manual', source: 'manual' };
    await store.saveActivities([fresh, stale, manual]);

    const removed = await store.pruneExpiredStrava(now);
    expect(removed).toBe(1);
    const kept = (await store.loadActivities()).map((a) => a.id).sort();
    expect(kept).toEqual(['fresh', 'manual']);
  });
});
