import type { CoachContext, TrainingPlan } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  analyzeWorkout,
  buildCoachContext,
  detectRedFlags,
  generatePlan,
  proposeNextWorkout,
  repairPlan,
  shouldHalt,
  validatePlan,
} from '../src/coach/index';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';

function contextWith(overrides: Partial<CoachContext>): CoachContext {
  return {
    generatedAt: '2026-07-09T00:00:00Z',
    profile: DEMO_PROFILE,
    recentActivities: [],
    ...overrides,
  };
}

describe('safety', () => {
  it('flags red-flag symptoms and halts', () => {
    const flags = detectRedFlags({ text: 'I felt chest pain and got dizzy' });
    expect(flags.some((f) => f.severity === 'stop')).toBe(true);
    expect(shouldHalt(flags)).toBe(true);
  });
  it('does not halt on a normal note', () => {
    expect(shouldHalt(detectRedFlags({ text: 'Legs felt great today' }))).toBe(false);
  });
});

describe('next-workout proposer', () => {
  it('recommends recovery when form is deeply negative', () => {
    const w = proposeNextWorkout(
      contextWith({ fitness: { date: '2026-07-08', ctl: 60, atl: 95, tsb: -30 } }),
      DEMO_PROFILE,
    );
    expect(w.type).toBe('recovery');
  });
  it('recommends quality when fresh and short on intensity', () => {
    const w = proposeNextWorkout(
      contextWith({
        fitness: { date: '2026-07-08', ctl: 50, atl: 45, tsb: 5 },
        weeklyDistribution: {
          easySec: 6000,
          moderateSec: 0,
          hardSec: 0,
          easyPct: 100,
          moderatePct: 0,
          hardPct: 0,
        },
      }),
      DEMO_PROFILE,
    );
    expect(['threshold', 'interval']).toContain(w.type);
  });
});

describe('plan generation + guardrail', () => {
  it('generates a valid periodized plan', async () => {
    const { plan, validation } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k', date: '2026-09-06' },
      weeks: 8,
      startDate: '2026-07-13',
      deps: { nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(plan.weeks).toHaveLength(8);
    expect(validation.valid).toBe(true);
    // phases progress base -> ... -> taper
    expect(plan.weeks[0].phase === 'base' || plan.weeks[0].phase === 'recovery').toBe(true);
    expect(plan.weeks[plan.weeks.length - 1].phase).toBe('taper');
  });

  it('flags and repairs back-to-back hard days', () => {
    const bad: TrainingPlan = {
      id: 'bad',
      createdAt: '2026-07-09T00:00:00Z',
      goal: { distance: '10k' },
      startDate: '2026-07-13',
      weeks: [
        {
          weekNumber: 1,
          phase: 'build',
          focus: 'test',
          targetTss: 300,
          days: [
            {
              day: 2,
              sessions: [
                {
                  type: 'threshold',
                  title: 'T',
                  description: 'd',
                  rationale: 'r',
                  targetTss: 60,
                  targetDurationSec: 3000,
                },
              ],
            },
            {
              day: 3,
              sessions: [
                {
                  type: 'interval',
                  title: 'I',
                  description: 'd',
                  rationale: 'r',
                  targetTss: 70,
                  targetDurationSec: 3000,
                },
              ],
            },
            {
              day: 5,
              sessions: [
                {
                  type: 'rest',
                  title: 'Rest',
                  description: 'd',
                  rationale: 'r',
                  targetTss: 0,
                  targetDurationSec: 0,
                },
              ],
            },
          ],
        },
      ],
    };
    const v = validatePlan(bad);
    expect(v.valid).toBe(false);
    expect(v.violations.some((x) => x.rule === 'back_to_back_hard')).toBe(true);

    const repaired = repairPlan(bad);
    expect(validatePlan(repaired.plan).violations.some((x) => x.rule === 'back_to_back_hard')).toBe(
      false,
    );
  });
});

describe('coach context + analysis (offline)', () => {
  it('builds a coach context from history', () => {
    const ctx = buildCoachContext({
      activities: [...demoHistory(), demoActivity()],
      profile: DEMO_PROFILE,
      goal: { distance: '10k', date: '2026-09-06' },
      asOfDate: '2026-07-08T00:00:00Z',
    });
    expect(ctx.fitness).toBeDefined();
    expect(ctx.recentActivities.length).toBeGreaterThan(0);
    expect(ctx.daysToRace).toBeGreaterThan(0);
  });

  it('analyzes a workout without an LLM', async () => {
    const result = await analyzeWorkout({ activity: demoActivity(), profile: DEMO_PROFILE });
    expect(result.headline).toContain('TSS');
    expect(result.explanation.length).toBeGreaterThan(20);
  });
});
