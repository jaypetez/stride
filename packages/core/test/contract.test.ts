import { ActivityMetrics, CoachContext, DailyLoad, TrainingPlan } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { buildCoachContext, generatePlan } from '../src/coach/index';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';
import { computeActivityMetrics, toDailyLoads } from '../src/science/index';

/**
 * The Zod schemas in @stride/schemas are the single source of truth, but core
 * outputs are only typed via `z.infer` and never validated at runtime (parsing
 * in hot compute paths would cost perf and add risk). These tests enforce the
 * contract instead: representative outputs must `.parse()` clean against their
 * schemas, so producer and schema can never silently drift apart.
 */
describe('schema contract (core output ⇄ @stride/schemas)', () => {
  it('computeActivityMetrics output satisfies ActivityMetrics', () => {
    const m = computeActivityMetrics(demoActivity(), DEMO_PROFILE);
    expect(() => ActivityMetrics.parse(m)).not.toThrow();
    expect(ActivityMetrics.parse(m).activityId).toBe(m.activityId);
  });

  it('buildCoachContext output satisfies CoachContext', () => {
    const ctx = buildCoachContext({
      activities: [...demoHistory(), demoActivity()],
      profile: DEMO_PROFILE,
      goal: { distance: '10k', date: '2026-09-06' },
      asOfDate: '2026-07-08T00:00:00Z',
    });
    expect(() => CoachContext.parse(ctx)).not.toThrow();
    // The tightened enums (sportType/loadMethod) must accept real producer data.
    expect(ctx.recentActivities.length).toBeGreaterThan(0);
  });

  it('a generated (deterministic) plan satisfies TrainingPlan', async () => {
    const { plan } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k' },
      weeks: 8,
      startDate: '2026-07-13',
      deps: { nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(() => TrainingPlan.parse(plan)).not.toThrow();
  });

  it('toDailyLoads items satisfy DailyLoad', () => {
    const loads = toDailyLoads(demoHistory(), DEMO_PROFILE);
    expect(loads.length).toBeGreaterThan(0);
    for (const load of loads) {
      expect(() => DailyLoad.parse(load)).not.toThrow();
    }
  });
});
