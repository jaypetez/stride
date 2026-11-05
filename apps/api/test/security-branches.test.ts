import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { Hono } from 'hono';
import { describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import { rateLimit, rateLimitsFromEnv } from '../src/security';

/**
 * Edge branches of the hardening middleware (AUD-017 keeps API branch coverage
 * at its threshold): IPv6 allowed hosts, an unparseable Host header, method-
 * scoped rate limits and a whole-body validation failure.
 */

let n = 0;
function makeApp(env: Record<string, string> = {}) {
  const dir = path.join(os.tmpdir(), `stride-api-branches-${process.pid}-${Date.now()}-${n++}`);
  return buildApp({ config: loadConfig(env), store: new LocalStore(dir), llm: null });
}

describe('hostGuard edge cases', () => {
  it('accepts a bare IPv6 STRIDE_API_ALLOWED_HOSTS entry against a bracketed Host', async () => {
    const app = makeApp({ STRIDE_API_ALLOWED_HOSTS: 'fd00::1,[fd00::2]' });
    for (const host of ['[fd00::1]:8720', '[fd00::2]']) {
      expect((await app.request('/health', { headers: { host } })).status, host).toBe(200);
    }
    expect((await app.request('/health', { headers: { host: '[fd00::3]' } })).status).toBe(403);
  });

  it('rejects an unparseable Host header with 403', async () => {
    const res = await makeApp().request('/health', { headers: { host: 'bad host^' } });
    expect(res.status).toBe(403);
    expect(((await res.json()) as { error: string }).error).toBe('Forbidden host');
  });
});

describe('rateLimit methods option', () => {
  it('only counts the listed methods', async () => {
    const app = new Hono()
      .use('*', rateLimit({ limit: 1, windowMs: 60_000, methods: ['post'], now: () => 1 }))
      .all('/x', (c) => c.text('ok'));
    expect((await app.request('/x', { method: 'POST' })).status).toBe(200);
    expect((await app.request('/x', { method: 'POST' })).status).toBe(429);
    // GETs are not counted, so they still pass after the POST bucket is spent.
    expect((await app.request('/x')).status).toBe(200);
    expect((await app.request('/x')).status).toBe(200);
  });
});

describe('validation envelope', () => {
  it('names the body when the whole JSON value has the wrong shape', async () => {
    const res = await makeApp().request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '[]',
    });
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: string }).error).toMatch(/^Invalid request — body: /);
  });
});

describe('rateLimitsFromEnv', () => {
  it('keeps the defaults when nothing valid is set', () => {
    expect(rateLimitsFromEnv({})).toEqual({});
    for (const bad of ['', ' ', '0', '-3', '2.5', 'lots']) {
      expect(rateLimitsFromEnv({ STRIDE_API_LLM_RATE_LIMIT: bad }), bad).toEqual({});
    }
  });

  it('parses per-minute overrides for the coach and sync buckets', () => {
    expect(
      rateLimitsFromEnv({ STRIDE_API_LLM_RATE_LIMIT: ' 1000 ', STRIDE_API_SYNC_RATE_LIMIT: '2' }),
    ).toEqual({
      llm: { limit: 1000, windowMs: 60_000 },
      sync: { limit: 2, windowMs: 60_000 },
    });
  });

  it('an override actually raises the coach limit past the default 30', async () => {
    const dir = path.join(os.tmpdir(), `stride-api-ratelimit-${process.pid}-${Date.now()}`);
    const app = buildApp(
      { config: loadConfig({}), store: new LocalStore(dir), llm: null },
      { rateLimits: rateLimitsFromEnv({ STRIDE_API_LLM_RATE_LIMIT: '40' }) },
    );
    const statuses: number[] = [];
    for (let i = 0; i < 41; i++) {
      statuses.push(
        (await app.request('/analyze/demo', { headers: { host: 'localhost' } })).status,
      );
    }
    expect(statuses.slice(0, 40).every((s) => s !== 429)).toBe(true);
    expect(statuses[40]).toBe(429);
  });
});
