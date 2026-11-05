import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { Hono } from 'hono';
import { describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import { rateLimit } from '../src/security';

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
