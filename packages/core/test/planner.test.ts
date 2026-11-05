import { type LlmPlanProposal, RaceGoal, WorkoutSuggestion } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  alignToRace,
  buildPlanSkeleton,
  makeSession,
  materializeProposal,
  validatePlan,
} from '../src/coach/index';
import { DEMO_PROFILE } from '../src/fixtures';

const THRESHOLD = 3.33;

describe('makeSession — no Infinity/NaN pace for paceIf=0 types', () => {
  it('cross_training omits pace, uses distance 0, and round-trips through the schema', () => {
    const s = makeSession('cross_training', 40, THRESHOLD);

    // The bug: pace = mpsToSecPerKm(threshold * 0) = Infinity, which
    // JSON-serializes to null and fails schema re-parse. Fixed: field omitted.
    expect(s.targetPaceSecPerKm).toBeUndefined();
    expect(s.targetDistanceM).toBe(0);
    // No field is ever Infinity/NaN.
    for (const v of Object.values(s)) {
      if (typeof v === 'number') expect(Number.isFinite(v)).toBe(true);
    }
    expect(s.targetDurationSec).toBe(2400);

    // Persisted plans are JSON: Infinity -> null would make this parse throw.
    const roundTripped = WorkoutSuggestion.parse(JSON.parse(JSON.stringify(s)));
    expect(roundTripped.type).toBe('cross_training');
    expect(roundTripped.targetPaceSecPerKm).toBeUndefined();
  });

  it('still emits a finite pace for a normal running type', () => {
    const s = makeSession('easy', 45, THRESHOLD);
    expect(typeof s.targetPaceSecPerKm).toBe('number');
    expect(Number.isFinite(s.targetPaceSecPerKm ?? Number.NaN)).toBe(true);
    expect((s.targetDistanceM ?? 0) > 0).toBe(true);
    expect(() => WorkoutSuggestion.parse(JSON.parse(JSON.stringify(s)))).not.toThrow();
  });
});

describe('materializeProposal — duplicate proposal keys (AUD-004)', () => {
  const params = {
    profile: DEMO_PROFILE,
    goal: RaceGoal.parse({ distance: '10k' }),
    weeks: 2,
    startDate: '2026-07-13',
    planId: 'p',
    createdAt: '2026-07-09T00:00:00Z',
  };
  const day = (dayOfWeek: number, workoutType: 'easy' | 'rest' | 'threshold' | 'long') => ({
    dayOfWeek,
    workoutType,
    emphasis: 'x',
    rationale: `${workoutType} on ${dayOfWeek}`,
  });

  it('dedupes a duplicate weekNumber, keeping the first proposed week', () => {
    const proposal: LlmPlanProposal = {
      weeks: [
        { weekNumber: 1, phase: 'base', days: [day(1, 'rest'), day(2, 'easy')] },
        { weekNumber: 1, phase: 'build', days: [day(1, 'threshold'), day(2, 'threshold')] },
        { weekNumber: 2, phase: 'base', days: [day(1, 'rest'), day(3, 'long')] },
      ],
    };
    const plan = materializeProposal(proposal, params);
    expect(plan.weeks.map((w) => w.weekNumber)).toEqual([1, 2]);
    expect(plan.weeks[0].phase).toBe('base');
    expect(plan.weeks[0].days.map((d) => d.sessions[0].type)).toEqual(['rest', 'easy']);
  });

  it('dedupes a duplicate dayOfWeek within a week, keeping the first', () => {
    const proposal: LlmPlanProposal = {
      weeks: [
        {
          weekNumber: 1,
          phase: 'base',
          days: [day(3, 'easy'), day(1, 'rest'), day(3, 'threshold'), day(1, 'long')],
        },
      ],
    };
    const plan = materializeProposal(proposal, params);
    const days = plan.weeks[0].days;
    expect(days.map((d) => d.day)).toEqual([1, 3]);
    expect(days.map((d) => d.sessions[0].type)).toEqual(['rest', 'easy']);
    expect(days[1].sessions[0].rationale).toBe('easy on 3');
  });

  it('aligns a materialized plan to the race and adds a race session', () => {
    const proposal: LlmPlanProposal = {
      weeks: [
        { weekNumber: 1, phase: 'base', days: [day(1, 'rest'), day(2, 'easy'), day(7, 'long')] },
        { weekNumber: 2, phase: 'taper', days: [day(1, 'rest'), day(3, 'easy')] },
      ],
    };
    const plan = materializeProposal(proposal, {
      ...params,
      goal: RaceGoal.parse({ distance: '10k', date: '2026-08-09' }),
    });
    expect(plan.endDate).toBe('2026-08-09');
    const race = plan.weeks[1].days.find((d) => d.day === 7);
    expect(race?.date).toBe('2026-08-09');
    expect(race?.sessions[0].type).toBe('race');
  });
});

describe('plan aligned to the goal race (AUD-003)', () => {
  const base = {
    profile: DEMO_PROFILE,
    startDate: '2026-07-09T00:00:00Z',
    planId: 'p',
    createdAt: '2026-07-09T00:00:00Z',
  };

  it('keeps the requested start/length without a goal date', () => {
    expect(alignToRace('2026-07-09T10:00:00Z', 8, RaceGoal.parse({ distance: '10k' }))).toEqual({
      startDate: '2026-07-09',
      weeks: 8,
    });
  });

  it('shrinks to the whole weeks that fit before a near race', () => {
    const a = alignToRace('2026-07-09', 8, RaceGoal.parse({ distance: '5k', date: '2026-08-02' }));
    expect(a).toEqual({ startDate: '2026-07-13', weeks: 3, raceDate: '2026-08-02' });
  });

  it('accepts a full ISO datetime goal date', () => {
    const a = alignToRace('2026-07-09', 4, RaceGoal.parse({ date: '2026-08-05T07:30:00Z' }));
    expect(a.raceDate).toBe('2026-08-05');
    expect(a.weeks).toBe(4);
  });

  it('ignores a race date before the start', () => {
    expect(alignToRace('2026-07-09', 4, RaceGoal.parse({ date: '2026-07-01' }))).toEqual({
      startDate: '2026-07-09',
      weeks: 4,
    });
  });

  for (const [distance, weeks, raceDate] of [
    ['10k', 8, '2026-09-06'],
    ['marathon', 16, '2026-11-01'],
    ['10k', 2, '2026-07-22'],
    ['5k', 1, '2026-07-15'],
  ] as const) {
    it(`${distance}, ${weeks} weeks: last week ends on race day with a race session and passes the guardrail`, () => {
      const plan = buildPlanSkeleton({
        ...base,
        goal: RaceGoal.parse({ distance, date: raceDate }),
        weeks,
      });
      expect(plan.endDate).toBe(raceDate);
      const last = plan.weeks[plan.weeks.length - 1];
      const day7 = last.days.find((d) => d.day === 7);
      expect(day7?.date).toBe(raceDate);
      expect(day7?.sessions[0].type).toBe('race');
      expect(day7?.sessions[0].date).toBe(raceDate);
      expect(validatePlan(plan).valid).toBe(true);
      expect(
        validatePlan(plan, { seedCtl: 35, seedAtl: 40, experienceLevel: 'beginner' }).valid,
      ).toBe(true);
    });
  }
});
