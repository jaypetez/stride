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

  it('POST /plan {kind: build} (demo) returns a seeded no-race fitness block', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ demo: true, kind: 'build', weeks: 8 }),
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.plan.kind).toBe('build');
    expect(body.plan.weeks).toHaveLength(8);
    expect(body.validation.valid).toBe(true);
    expect(body.seed).toBeDefined();
    const phases = body.plan.weeks.map((w: any) => w.phase);
    expect(phases).not.toContain('taper');
    expect(phases).not.toContain('peak');
    const types = body.plan.weeks.flatMap((w: any) =>
      w.days.flatMap((d: any) => d.sessions.map((s: any) => s.type)),
    );
    expect(types).not.toContain('race');
  });

  it('POST /plan {kind: build} rejects weeks outside 4-16 with a 400 envelope', async () => {
    for (const weeks of [3, 17]) {
      const res = await app.request('/plan', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ demo: true, kind: 'build', weeks }),
      });
      expect(res.status).toBe(400);
      const body = (await res.json()) as any;
      expect(typeof body.error).toBe('string');
      expect(typeof body.requestId).toBe('string');
    }
    const bad = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ demo: true, kind: 'marathon-ish' }),
    });
    expect(bad.status).toBe(400);
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
    const res = await app.request('/sync', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
    });
    expect(res.status).toBe(500);
    const body = (await res.json()) as any;
    // Generic message only — the internal cause stays in the logs (AUD-012).
    expect(body.error).toBe('Internal Server Error');
    expect(Object.keys(body).sort()).toEqual(['error', 'requestId']);
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

  describe('PAR-Q answer completeness (AUD-001)', () => {
    const screenApp = () =>
      buildApp({
        config: loadConfig({}),
        store: new LocalStore(
          path.join(os.tmpdir(), `stride-api-parq-${process.pid}-${Date.now()}-${Math.random()}`),
        ),
        llm: null,
      });
    const post = (a: ReturnType<typeof buildApp>, answers: unknown) =>
      a.request('/profile/screening', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ answers }),
      });

    it.each([
      ['empty', []],
      ['short', [false, false]],
      ['long', [false, false, false, false, false, false, false, false]],
    ])(
      'rejects a %s answer list with 400 and leaves the profile unchanged',
      async (_name, answers) => {
        const a = screenApp();
        const before = (await (await a.request('/profile')).json()) as any;
        const res = await post(a, answers);
        expect(res.status).toBe(400);
        const body = (await res.json()) as any;
        expect(body.error).toMatch(/answers/);
        const after = (await (await a.request('/profile')).json()) as any;
        expect(after).toEqual(before);
        expect(after.medicalClearance).not.toBe(true);
      },
    );

    it('clears a complete all-no answer list', async () => {
      const res = await post(screenApp(), [false, false, false, false, false, false, false]);
      expect(res.status).toBe(200);
      const body = (await res.json()) as any;
      expect(body.screening.cleared).toBe(true);
      expect(body.profile.medicalClearance).toBe(true);
    });
  });

  describe('plan date validation and race alignment (AUD-003)', () => {
    const postPlan = (body: unknown) =>
      app.request('/plan', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });

    it.each([
      ['start', { demo: true, start: 'garbage' }],
      ['start (impossible date)', { demo: true, start: '2026-02-30' }],
      ['date', { demo: true, date: '09/06/2026' }],
    ])('rejects an invalid %s with 400 before planning', async (_name, body) => {
      const res = await postPlan(body);
      expect(res.status).toBe(400);
      const json = (await res.json()) as any;
      expect(json.error).toMatch(/YYYY-MM-DD|calendar date/);
    });

    it('ends the last week on race day with a race session', async () => {
      const res = await postPlan({
        demo: true,
        race: '10k',
        weeks: 6,
        start: '2026-07-13',
        date: '2026-09-06',
      });
      expect(res.status).toBe(200);
      const { plan, validation } = (await res.json()) as any;
      expect(validation.valid).toBe(true);
      expect(plan.endDate).toBe('2026-09-06');
      const last = plan.weeks[plan.weeks.length - 1];
      const raceDay = last.days.find((d: any) => d.date === '2026-09-06');
      expect(raceDay.sessions[0].type).toBe('race');
    });
  });
});
