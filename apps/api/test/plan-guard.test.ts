import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { describe, expect, it, vi } from 'vitest';

// Force the plan engine to return a plan that is STILL invalid after repair (in
// practice the deterministic skeleton is always valid, so this can only be
// provoked by stubbing) and assert the API never persists it.
vi.mock('@stride/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@stride/core')>();
  return {
    ...actual,
    generatePlan: vi.fn(async (params: Parameters<typeof actual.generatePlan>[0]) => {
      const result = await actual.generatePlan(params);
      return {
        ...result,
        validation: {
          valid: false,
          repaired: true,
          violations: [
            {
              rule: 'ramp',
              severity: 'error' as const,
              weekNumber: 2,
              message: 'Week 2 load jumps 80% over the previous loading week (cap is 35%).',
            },
          ],
        },
      };
    }),
  };
});

const { buildApp } = await import('../src/app');

describe('POST /plan never saves an invalid plan (AUD-003)', () => {
  it('returns 422 with the violations and persists nothing', async () => {
    const store = new LocalStore(
      path.join(os.tmpdir(), `stride-api-422-${process.pid}-${Date.now()}`),
    );
    const app = buildApp({ config: loadConfig({}), store, llm: null });

    const res = await app.request('/plan', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ race: '10k', weeks: 4 }),
    });
    expect(res.status).toBe(422);
    const body = (await res.json()) as any;
    expect(body.error).toMatch(/not saved/);
    expect(typeof body.requestId).toBe('string');
    expect(body.validation.valid).toBe(false);
    expect(body.plan).toBeUndefined();

    expect(await store.loadPlan()).toBeNull();
    expect(await store.loadGoal()).toBeNull();
    expect((await app.request('/plan')).status).toBe(404);
  });
});
