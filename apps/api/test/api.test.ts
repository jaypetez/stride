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

  it('rejects an invalid plan body', async () => {
    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ weeks: 'not-a-number' }),
    });
    expect(res.status).toBe(400);
  });
});
