import type { PlanPhase, PlanWeek, TrainingPlan, WorkoutSuggestion } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { makeSession, repairPlan, validatePlan } from '../src/coach/index';

const THRESHOLD = 3.33;

/** Build a single-session-per-day week from `[day, session]` tuples. */
function week(
  weekNumber: number,
  phase: PlanPhase,
  sessions: [number, WorkoutSuggestion][],
): PlanWeek {
  return {
    weekNumber,
    phase,
    focus: 'test',
    days: sessions.map(([day, s]) => ({ day, sessions: [s] })),
  };
}

function plan(weeks: PlanWeek[]): TrainingPlan {
  return {
    id: 'test-plan',
    createdAt: '2026-07-09T00:00:00Z',
    goal: { distance: '10k' },
    startDate: '2026-07-13',
    weeks,
  };
}

const rest = (day: number) =>
  [day, makeSession('rest', 0, THRESHOLD)] as [number, WorkoutSuggestion];
const easy = (day: number, min: number) =>
  [day, makeSession('easy', min, THRESHOLD)] as [number, WorkoutSuggestion];
const long = (day: number, min: number) =>
  [day, makeSession('long', min, THRESHOLD)] as [number, WorkoutSuggestion];
const tempo = (day: number, min: number) =>
  [day, makeSession('tempo', min, THRESHOLD)] as [number, WorkoutSuggestion];
const threshold = (day: number, min: number) =>
  [day, makeSession('threshold', min, THRESHOLD)] as [number, WorkoutSuggestion];

const SEED = { seedCtl: 0, seedAtl: 0, experienceLevel: 'intermediate' as const };

describe('guardrail — CTL ramp cap (GOAL §7)', () => {
  // Two heavy build weeks starting from zero fitness: CTL rises far faster than
  // the 5.5 pt/week cap for an intermediate athlete.
  const heavyWeek = (n: number) =>
    week(n, 'build', [
      rest(1),
      threshold(2, 90),
      easy(3, 60),
      tempo(4, 80),
      rest(5),
      easy(6, 60),
      long(7, 150),
    ]);
  const overload = plan([heavyWeek(1), heavyWeek(2)]);

  it('flags a week whose CTL rise exceeds the cap', () => {
    const v = validatePlan(overload, SEED);
    expect(v.valid).toBe(false);
    const ramp = v.violations.filter((x) => x.rule === 'ramp');
    expect(ramp.length).toBeGreaterThan(0);
    expect(ramp[0].severity).toBe('error');
  });

  it('repairs the over-ramp by scaling quality/long volume down', () => {
    const repaired = repairPlan(overload, SEED);
    expect(repaired.fixed.some((f) => f.rule === 'ramp')).toBe(true);
    const after = validatePlan(repaired.plan, SEED);
    expect(after.violations.some((x) => x.rule === 'ramp')).toBe(false);
  });

  it('falls back to the week-over-week TSS ratio when no seed is provided', () => {
    // Week 2 jumps far more than 35% over week 1 — flagged without any seed.
    const spike = plan([
      week(1, 'build', [rest(1), easy(2, 40), rest(3), easy(4, 40), rest(5), rest(6), long(7, 45)]),
      week(2, 'build', [
        rest(1),
        threshold(2, 90),
        easy(3, 60),
        tempo(4, 80),
        rest(5),
        easy(6, 60),
        long(7, 150),
      ]),
    ]);
    const v = validatePlan(spike);
    expect(v.violations.some((x) => x.rule === 'ramp')).toBe(true);
  });
});

describe('guardrail — back-to-back-hard downgrade uses the athlete anchor', () => {
  // A fast athlete whose real threshold (4.0 m/s) is far from the 3.0 default.
  const FAST = 4.0;
  const bad = plan([
    week(1, 'build', [
      rest(1),
      [2, makeSession('threshold', 50, FAST)],
      [3, makeSession('interval', 50, FAST)], // hard on consecutive days
      rest(4),
      [5, makeSession('easy', 40, FAST)],
      [6, makeSession('easy', 40, FAST)],
      rest(7),
    ]),
  ]);

  it('paces the downgraded easy day off the recovered threshold, not the 3.0 default', () => {
    const repaired = repairPlan(bad);
    const day3 = repaired.plan.weeks[0].days.find((d) => d.day === 3);
    expect(day3?.sessions[0].type).toBe('easy');

    // Pace/distance if computed from the athlete's real 4.0 m/s anchor (the
    // threshold is recovered from the session's rounded pace, so distance is
    // within rounding of the exact-anchor value).
    const anchored = makeSession('easy', 50, FAST);
    const buggyDefault = makeSession('easy', 50, 3.0);
    expect(day3?.sessions[0].targetPaceSecPerKm).toBe(anchored.targetPaceSecPerKm);
    expect(
      Math.abs((day3?.sessions[0].targetDistanceM ?? 0) - (anchored.targetDistanceM ?? 0)),
    ).toBeLessThan((anchored.targetDistanceM ?? 0) * 0.01);
    // Sanity: the (buggy) 3.0-default pace/distance would have been clearly off.
    expect(day3?.sessions[0].targetPaceSecPerKm).not.toBe(buggyDefault.targetPaceSecPerKm);
    expect(day3?.sessions[0].targetDistanceM ?? 0).toBeGreaterThan(
      buggyDefault.targetDistanceM ?? 0,
    );
  });
});

describe('guardrail — weekly rest minimum', () => {
  const noRest = plan([
    week(1, 'base', [
      easy(1, 40),
      easy(2, 40),
      easy(3, 40),
      easy(4, 40),
      easy(5, 40),
      easy(6, 40),
      easy(7, 30),
    ]),
  ]);

  it('flags a week with no rest day as an error', () => {
    const v = validatePlan(noRest);
    expect(v.valid).toBe(false);
    expect(v.violations.some((x) => x.rule === 'rest_minimum' && x.severity === 'error')).toBe(
      true,
    );
  });

  it('repairs it by converting the lowest-load day to rest', () => {
    const repaired = repairPlan(noRest);
    expect(repaired.fixed.some((f) => f.rule === 'rest_minimum')).toBe(true);
    const after = validatePlan(repaired.plan);
    expect(after.violations.some((x) => x.rule === 'rest_minimum')).toBe(false);
    // The 30-min day (lowest load) became the rest day.
    const restDay = repaired.plan.weeks[0].days.find((d) => d.sessions[0].type === 'rest');
    expect(restDay?.day).toBe(7);
  });
});

describe('guardrail — long-run cap', () => {
  const bigLong = plan([
    week(1, 'base', [rest(1), easy(2, 30), easy(3, 30), rest(4), rest(5), rest(6), long(7, 180)]),
  ]);

  it('flags a long run over the volume fraction', () => {
    const v = validatePlan(bigLong);
    expect(v.valid).toBe(false);
    expect(v.violations.some((x) => x.rule === 'long_run_cap' && x.severity === 'error')).toBe(
      true,
    );
  });

  it('repairs it by shrinking the long run under the cap', () => {
    const repaired = repairPlan(bigLong);
    expect(repaired.fixed.some((f) => f.rule === 'long_run_cap')).toBe(true);
    const after = validatePlan(repaired.plan);
    expect(after.violations.some((x) => x.rule === 'long_run_cap')).toBe(false);
  });
});

describe('guardrail — unrepairable plan is rejected', () => {
  // All-easy overload for a beginner (cap 4): the ramp repairer only scales
  // quality/long volume, so there is nothing it can trim — it stays invalid.
  const easyOverload = plan([
    week(1, 'build', [
      easy(1, 90),
      easy(2, 90),
      easy(3, 90),
      rest(4),
      easy(5, 90),
      easy(6, 90),
      rest(7),
    ]),
  ]);
  const beginnerSeed = { seedCtl: 0, seedAtl: 0, experienceLevel: 'beginner' as const };

  it('reports valid:false after an unsuccessful repair', () => {
    expect(validatePlan(easyOverload, beginnerSeed).valid).toBe(false);
    const repaired = repairPlan(easyOverload, beginnerSeed);
    const after = validatePlan(repaired.plan, beginnerSeed);
    expect(after.valid).toBe(false);
    expect(after.violations.some((x) => x.rule === 'ramp')).toBe(true);
  });
});
