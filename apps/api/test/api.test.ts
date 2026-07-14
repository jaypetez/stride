import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';

function makeApp() {
  const dir = path.join(os.tmpdir(), `stride-api-test-${process.pid}-${Date.now()}`);
  const config = loadConfig({});
  return buildApp({ config, store: new LocalStore(dir), llm: null });
}

describe('Stride API', () => {
  const app = makeApp();

  it('GET /health returns ok', async () => {
    const res = await app.request('/health');
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.status).toBe('ok');
  });

  it('GET /analyze/demo returns metrics + analysis', async () => {
    const res = await app.request('/analyze/demo');
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.metrics.tss).toBeGreaterThan(0);
    expect(body.analysis.headline).toContain('TSS');
  });

  it('GET /next?demo=true returns a workout and form', async () => {
    const res = await app.request('/next?demo=true');
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.workout.type).toBeDefined();
    expect(body.context.fitness).toBeDefined();
  });

  it('POST /plan (demo) returns a valid 6-week plan', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ demo: true, race: '10k', weeks: 6 }),
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.plan.weeks).toHaveLength(6);
    expect(body.validation.valid).toBe(true);
  });

  it('GET /analyze/demo includes the safety disclaimer', async () => {
    const res = await app.request('/analyze/demo');
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(typeof body.disclaimer).toBe('string');
    expect(body.disclaimer.length).toBeGreaterThan(0);
    expect(body.analysis.disclaimer).toBe(body.disclaimer);
    expect(Array.isArray(body.flags)).toBe(true);
  });

  it('GET /next?demo includes the disclaimer on the workout envelope', async () => {
    const res = await app.request('/next?demo=true');
    const body = (await res.json()) as any;
    expect(typeof body.disclaimer).toBe('string');
    expect(body.workout.disclaimer).toBe(body.disclaimer);
  });

  it('rejects an invalid plan body with the { error, requestId } envelope + header', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ weeks: 'not-a-number' }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as any;
    expect(typeof body.error).toBe('string');
    expect(typeof body.requestId).toBe('string');
    // The generated request id is echoed on the response header and body.
    expect(res.headers.get('x-request-id')).toBe(body.requestId);
  });

  it('routes a forced failure (offline /sync) through onError as { error, requestId } + 500', async () => {
    // No Strava credentials + no tokens => syncStrava throws; the local try/catch
    // is gone, so this must surface via onError with a matching x-request-id.
    const res = await app.request('/sync', { method: 'POST' });
    expect(res.status).toBe(500);
    const body = (await res.json()) as any;
    expect(typeof body.error).toBe('string');
    expect(typeof body.requestId).toBe('string');
    expect(res.headers.get('x-request-id')).toBe(body.requestId);
  });

  it('returns a { error, requestId } 404 for an unknown activity', async () => {
    const res = await app.request('/analyze/does-not-exist');
    expect(res.status).toBe(404);
    const body = (await res.json()) as any;
    expect(body.error).toContain('not found');
    expect(typeof body.requestId).toBe('string');
    expect(res.headers.get('x-request-id')).toBe(body.requestId);
  });

  it('restricts CORS to the configured web origin (never *)', async () => {
    const res = await app.request('/health', {
      headers: { origin: 'http://localhost:5173' },
    });
    const acao = res.headers.get('access-control-allow-origin');
    expect(acao).not.toBe('*');
    expect(acao).toBe('http://localhost:5173');
  });

  it('POST /profile/screening runs PAR-Q and persists clearance + flags', async () => {
    const dir = path.join(os.tmpdir(), `stride-api-screen-${process.pid}-${Date.now()}`);
    const screenApp = buildApp({ config: loadConfig({}), store: new LocalStore(dir), llm: null });

    const flagged = await screenApp.request('/profile/screening', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      // "yes" to chest pain on exertion (question index 1) => not cleared.
      body: JSON.stringify({ answers: [false, true, false, false, false, false, false] }),
    });
    expect(flagged.status).toBe(200);
    const body = (await flagged.json()) as any;
    expect(body.screening.cleared).toBe(false);
    expect(body.profile.medicalClearance).toBe(false);
    expect(body.profile.healthFlags.length).toBeGreaterThan(0);

    // The persisted profile is reflected on a subsequent GET.
    const profile = (await (await screenApp.request('/profile')).json()) as any;
    expect(profile.healthFlags).toEqual(body.profile.healthFlags);
  });
});
