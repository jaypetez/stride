import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig, MAX_NOTE_LENGTH, StravaApiError } from '@stride/core';
import { describe, expect, it } from 'vitest';
import { type ApiOptions, buildApp } from '../src/app';

let n = 0;
function tmpDir(): string {
  return path.join(os.tmpdir(), `stride-api-sec-${process.pid}-${Date.now()}-${n++}`);
}

function makeApp(env: Record<string, string> = {}, options: ApiOptions = {}, store?: LocalStore) {
  return buildApp(
    { config: loadConfig(env), store: store ?? new LocalStore(tmpDir()), llm: null },
    options,
  );
}

const JSON_HEADERS = { 'content-type': 'application/json' };

async function body(res: Response): Promise<any> {
  return res.json();
}

describe('AUD-010 optional bearer token', () => {
  it('is open (no token configured) by default', async () => {
    const res = await makeApp().request('/health');
    expect(res.status).toBe(200);
  });

  it('returns 401 { error, requestId } without the bearer token when STRIDE_API_TOKEN is set', async () => {
    const app = makeApp({ STRIDE_API_TOKEN: 's3cret' });
    for (const headers of [{}, { authorization: 'Bearer wrong' }, { authorization: 's3cret' }]) {
      const res = await app.request('/health', { headers });
      expect(res.status).toBe(401);
      const b = await body(res);
      expect(b.error).toBe('Unauthorized');
      expect(b.requestId).toBe(res.headers.get('x-request-id'));
      expect(res.headers.get('www-authenticate')).toMatch(/^Bearer/);
    }
  });

  it('accepts the correct bearer token', async () => {
    const app = makeApp({ STRIDE_API_TOKEN: 's3cret' });
    const res = await app.request('/next?demo=true', {
      headers: { authorization: 'Bearer s3cret' },
    });
    expect(res.status).toBe(200);
  });

  it('still answers the CORS preflight (browsers send no credentials on it)', async () => {
    const app = makeApp({ STRIDE_API_TOKEN: 's3cret' });
    const res = await app.request('/plan', {
      method: 'OPTIONS',
      headers: {
        origin: 'http://localhost:5173',
        'access-control-request-method': 'POST',
        'access-control-request-headers': 'authorization,content-type',
      },
    });
    expect(res.status).toBe(204);
    expect(res.headers.get('access-control-allow-origin')).toBe('http://localhost:5173');
  });
});

describe('AUD-011 Host allowlist (DNS rebinding)', () => {
  const app = makeApp();

  it('rejects a foreign Host header with 403', async () => {
    const res = await app.request('/health', { headers: { host: 'evil.example' } });
    expect(res.status).toBe(403);
    const b = await body(res);
    expect(b.error).toBe('Forbidden host');
    expect(typeof b.requestId).toBe('string');
  });

  it('rejects a foreign host in the request URL with 403', async () => {
    const res = await app.request('http://evil.example:8720/health');
    expect(res.status).toBe(403);
  });

  it('accepts loopback names, with or without a port', async () => {
    for (const host of ['localhost', 'localhost:8720', '127.0.0.1:8720', '[::1]:8720']) {
      const res = await app.request('/health', { headers: { host } });
      expect(res.status, host).toBe(200);
    }
  });

  it('accepts the configured bind host and STRIDE_API_ALLOWED_HOSTS entries', async () => {
    const lan = makeApp({
      STRIDE_API_HOST: '192.168.1.5',
      STRIDE_API_ALLOWED_HOSTS: 'stride.lan',
    });
    expect((await lan.request('/health', { headers: { host: '192.168.1.5:8720' } })).status).toBe(
      200,
    );
    expect((await lan.request('/health', { headers: { host: 'stride.lan' } })).status).toBe(200);
    expect((await lan.request('/health', { headers: { host: 'evil.example' } })).status).toBe(403);
  });

  it('does not treat a wildcard bind (0.0.0.0) as an allowed Host', async () => {
    const wild = makeApp({ STRIDE_API_HOST: '0.0.0.0' });
    expect((await wild.request('/health', { headers: { host: '0.0.0.0:8720' } })).status).toBe(403);
  });
});

describe('AUD-011 JSON-only state changes (CSRF)', () => {
  const app = makeApp();

  for (const type of [
    'text/plain',
    'application/x-www-form-urlencoded',
    'multipart/form-data; boundary=x',
  ]) {
    it(`rejects a ${type} POST /plan with 415`, async () => {
      const res = await app.request('/plan', {
        method: 'POST',
        headers: { 'content-type': type },
        body: JSON.stringify({ demo: true }),
      });
      expect(res.status).toBe(415);
      expect(typeof (await body(res)).requestId).toBe('string');
    });
  }

  it('rejects a POST /sync with no content type with 415 (a "simple" request)', async () => {
    const res = await app.request('/sync', { method: 'POST' });
    expect(res.status).toBe(415);
  });

  it('rejects a form-encoded POST /profile/screening with 415', async () => {
    const res = await app.request('/profile/screening', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: 'answers=1',
    });
    expect(res.status).toBe(415);
  });

  it('accepts application/json with a charset parameter', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ demo: true, weeks: 4 }),
    });
    expect(res.status).toBe(200);
  });

  it('returns 400 (not 500) for malformed JSON', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: '{not json',
    });
    expect(res.status).toBe(400);
    expect(typeof (await body(res)).requestId).toBe('string');
  });
});

describe('AUD-011 secure headers and body limit', () => {
  it('sets secure headers on responses', async () => {
    const res = await makeApp().request('/health');
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
    expect(res.headers.get('x-frame-options')).toBe('SAMEORIGIN');
    expect(res.headers.get('content-security-policy')).toContain("default-src 'none'");
    expect(res.headers.get('x-powered-by')).toBeNull();
  });

  it('sets secure headers on error responses too', async () => {
    const res = await makeApp().request('/health', { headers: { host: 'evil.example' } });
    expect(res.status).toBe(403);
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
  });

  it('rejects a body over the limit with 413', async () => {
    const app = makeApp({}, { bodyLimitBytes: 1024 });
    const res = await app.request('/plan', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ demo: true, start: 'x'.repeat(2048) }),
    });
    expect(res.status).toBe(413);
    expect(typeof (await body(res)).requestId).toBe('string');
  });

  it('rejects a body over the default 64 KiB limit with 413', async () => {
    const res = await makeApp().request('/plan', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ demo: true, start: 'x'.repeat(70 * 1024) }),
    });
    expect(res.status).toBe(413);
  });
});

describe('AUD-011 rate limits', () => {
  it('returns 429 from /sync when called in a tight loop', async () => {
    const app = makeApp({}, { rateLimits: { sync: { limit: 3, windowMs: 60_000 } } });
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) {
      const res = await app.request('/sync', { method: 'POST', headers: JSON_HEADERS });
      statuses.push(res.status);
    }
    // Offline sync fails (500) but still counts; the 4th+ calls are throttled.
    expect(statuses).toEqual([500, 500, 500, 429, 429]);
  });

  it('returns 429 from the LLM routes (shared bucket) with Retry-After', async () => {
    const app = makeApp({}, { rateLimits: { llm: { limit: 3, windowMs: 60_000 } } });
    expect((await app.request('/analyze/demo')).status).toBe(200);
    expect((await app.request('/next?demo=true')).status).toBe(200);
    expect(
      (
        await app.request('/plan', {
          method: 'POST',
          headers: JSON_HEADERS,
          body: JSON.stringify({ demo: true, weeks: 4 }),
        })
      ).status,
    ).toBe(200);
    const res = await app.request('/next?demo=true');
    expect(res.status).toBe(429);
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(typeof (await body(res)).requestId).toBe('string');
    // Cheap, non-LLM routes are not throttled.
    expect((await app.request('/pmc?demo=true')).status).toBe(200);
    expect((await app.request('/plan')).status).toBe(404);
  });
});

describe('AUD-012 error bodies and request ids', () => {
  class FailingStore extends LocalStore {
    constructor(private readonly err: Error) {
      super(tmpDir());
    }
    override async loadPlan(): Promise<never> {
      throw this.err;
    }
  }

  it('500 bodies carry only { error, requestId } with a generic message', async () => {
    const app = makeApp({}, {}, new FailingStore(new Error('ENOENT: /home/me/.stride/secret')));
    const res = await app.request('/plan');
    expect(res.status).toBe(500);
    const b = await body(res);
    expect(Object.keys(b).sort()).toEqual(['error', 'requestId']);
    expect(b.error).toBe('Internal Server Error');
    expect(JSON.stringify(b)).not.toContain('secret');
  });

  it('502 bodies carry only { error, requestId } with a generic message', async () => {
    const app = makeApp(
      {},
      {},
      new FailingStore(new StravaApiError(500, 'Strava API /athlete failed (500): raw body')),
    );
    const res = await app.request('/plan');
    expect(res.status).toBe(502);
    const b = await body(res);
    expect(Object.keys(b).sort()).toEqual(['error', 'requestId']);
    expect(b.error).toBe('Upstream Strava request failed');
  });

  it('echoes a well-formed x-request-id', async () => {
    const res = await makeApp().request('/health', { headers: { 'x-request-id': 'abc_DEF-123' } });
    expect(res.headers.get('x-request-id')).toBe('abc_DEF-123');
  });

  it('replaces malformed request ids instead of echoing them', async () => {
    const app = makeApp();
    for (const bad of ['has space', 'x'.repeat(65), '<script>', 'aéb', 'semi;colon']) {
      const res = await app.request('/analyze/does-not-exist', {
        headers: { 'x-request-id': bad },
      });
      const id = res.headers.get('x-request-id');
      expect(id, bad).not.toBe(bad);
      expect(id).toMatch(/^[0-9a-f-]{36}$/);
      expect((await body(res)).requestId).toBe(id);
    }
  });
});

describe('AUD-012 note length', () => {
  const app = makeApp();
  const tooLong = 'x'.repeat(MAX_NOTE_LENGTH + 1);

  it('rejects a POST /plan note over the limit with 400', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ demo: true, note: tooLong }),
    });
    expect(res.status).toBe(400);
    expect((await body(res)).error).toMatch(/note/);
  });

  it('rejects an over-long note query on /next and /analyze', async () => {
    const q = encodeURIComponent(tooLong);
    expect((await app.request(`/next?demo=true&note=${q}`)).status).toBe(400);
    expect((await app.request(`/analyze/demo?note=${q}`)).status).toBe(400);
  });

  it('accepts a note at the limit', async () => {
    const ok = 'x'.repeat(MAX_NOTE_LENGTH);
    expect((await app.request(`/next?demo=true&note=${ok}`)).status).toBe(200);
  });
});
