import { describe, expect, it } from 'vitest';
import type { StravaConfig } from '../src/config';
import { StravaClient, StravaRateLimitError } from '../src/strava/index';

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
  scopes: 'read',
  apiBase: 'https://api.test/v3',
};
const tokens = { accessToken: 'a', refreshToken: 'r', expiresAt: 9_999_999_999 };

const LOW_HEADERS = {
  'x-ratelimit-limit': '200,2000',
  'x-ratelimit-usage': '5,10',
  'x-readratelimit-limit': '100,1000',
  'x-readratelimit-usage': '5,10',
};

describe('StravaClient 429 handling', () => {
  it('retries after Retry-After then succeeds', async () => {
    const sleeps: number[] = [];
    let call = 0;
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1000,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () => {
        call++;
        if (call === 1) return jsonResponse({ message: 'rate' }, 429, { 'retry-after': '1' });
        return jsonResponse({ id: 42 }, 200, LOW_HEADERS);
      },
    });
    const athlete = await client.getAthlete();
    expect(athlete.id).toBe(42);
    expect(sleeps).toEqual([1000]); // Retry-After: 1s
    expect(call).toBe(2);
  });

  it('degrades to StravaRateLimitError after exhausting retries', async () => {
    const sleeps: number[] = [];
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1000,
      maxRetries: 2,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () => jsonResponse({ message: 'rate' }, 429, { 'retry-after': '1' }),
    });
    await expect(client.getAthlete()).rejects.toBeInstanceOf(StravaRateLimitError);
    expect(sleeps).toEqual([1000, 1000]); // 2 retries then throw
  });
});

describe('StravaClient proactive throttle', () => {
  it('sleeps to the next 15-min window when near the short limit', async () => {
    const sleeps: number[] = [];
    let call = 0;
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1000, // 1000 % 900 = 100 into the window → 800s to boundary
      throttleMargin: 2,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () => {
        call++;
        // First response reports usage right at the short limit (199/200).
        if (call === 1)
          return jsonResponse({ id: 1 }, 200, { ...LOW_HEADERS, 'x-ratelimit-usage': '199,50' });
        return jsonResponse({ id: 2 }, 200, LOW_HEADERS);
      },
    });
    await client.getAthlete(); // primes rate-limit snapshot (no throttle yet)
    await client.getAthlete(); // near short limit → sleep to boundary
    expect(sleeps).toEqual([800_000]);
    expect(call).toBe(2);
  });

  it('computes the window boundary from the injected clock', async () => {
    const sleeps: number[] = [];
    let call = 0;
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1350, // 1350 % 900 = 450 → 450s to boundary
      throttleMargin: 2,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () => {
        call++;
        if (call === 1)
          return jsonResponse({ id: 1 }, 200, { ...LOW_HEADERS, 'x-ratelimit-usage': '199,50' });
        return jsonResponse({ id: 2 }, 200, LOW_HEADERS);
      },
    });
    await client.getAthlete();
    await client.getAthlete();
    expect(sleeps).toEqual([450_000]);
  });

  it('degrades (throws) when the daily sublimit is exhausted — never sleeps out a day', async () => {
    const sleeps: number[] = [];
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1000,
      throttleMargin: 2,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () =>
        jsonResponse({ id: 1 }, 200, { ...LOW_HEADERS, 'x-ratelimit-usage': '5,2000' }),
    });
    await client.getAthlete(); // primes daily usage at the cap (2000/2000)
    await expect(client.getAthlete()).rejects.toBeInstanceOf(StravaRateLimitError);
    expect(sleeps).toEqual([]); // cannot wait out a day
  });

  it('degrades when the wait to the window exceeds maxWaitMs', async () => {
    const sleeps: number[] = [];
    const client = new StravaClient({
      config,
      tokens,
      now: () => 1000,
      throttleMargin: 2,
      maxWaitMs: 1000, // boundary is ~800s away → exceeds budget
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      fetchImpl: async () =>
        jsonResponse({ id: 1 }, 200, { ...LOW_HEADERS, 'x-ratelimit-usage': '199,50' }),
    });
    await client.getAthlete();
    await expect(client.getAthlete()).rejects.toBeInstanceOf(StravaRateLimitError);
    expect(sleeps).toEqual([]);
  });
});
