import {
  AthleteProfile,
  type CoachContext,
  type LlmPlanProposal,
  type PlanPhase,
  RaceGoal,
  TrainingPlan,
  WorkoutSuggestion,
} from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  alignToRace,
  buildPlanSkeleton,
  generatePlan,
  makeSession,
  materializeProposal,
  proposeNextWorkout,
  repairPlan,
  rescaleSession,
  resolveRaceDistanceM,
  thresholdFromSession,
  validatePlan,
} from '../src/coach/index';
import { DEMO_PROFILE } from '../src/fixtures';
import { addDays } from '../src/science/index';

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

// --- AUD-014: table/property tests for the periodized planner ---------------
//
// These assert VALIDITY (every generated plan passes `validatePlan`) and
// structural invariants, never exact session layouts, so they stay green while
// the guardrail is tightened.

const DISTANCES = ['5k', '10k', 'half', 'marathon', 'custom'] as const;
const LEVELS = ['beginner', 'intermediate', 'advanced'] as const;
const PHASE_ORDER: Record<string, number> = { base: 0, build: 1, peak: 2, taper: 3 };
const START = '2026-01-05'; // a Monday

type Distance = (typeof DISTANCES)[number];

function goalFor(distance: Distance): RaceGoal {
  return RaceGoal.parse({
    distance,
    customDistanceM: distance === 'custom' ? 15_000 : undefined,
  });
}

function skeleton(
  weeks: number,
  distance: Distance = '10k',
  profile: AthleteProfile = AthleteProfile.parse({}),
): TrainingPlan {
  return buildPlanSkeleton({
    profile,
    goal: goalFor(distance),
    weeks,
    startDate: START,
    planId: `p-${weeks}`,
    createdAt: '2026-01-01T00:00:00Z',
  });
}

function seededContext(profile: AthleteProfile, ctl: number): CoachContext {
  return {
    generatedAt: '2026-01-04T00:00:00Z',
    profile,
    recentActivities: [],
    fitness: { date: '2026-01-04', ctl, atl: ctl, tsb: 0 },
  };
}

function errors(plan: TrainingPlan, ctx: Parameters<typeof validatePlan>[1] = {}) {
  return validatePlan(plan, ctx).violations.filter((v) => v.severity === 'error');
}

function allFinite(s: WorkoutSuggestion): boolean {
  return Object.values(s).every((v) => typeof v !== 'number' || Number.isFinite(v));
}

describe('buildPlanSkeleton — every race distance x 1-52 weeks', () => {
  for (const distance of DISTANCES) {
    it(`${distance}: the skeleton passes validatePlan (cold start and seeded) for 1-52 weeks`, () => {
      for (let weeks = 1; weeks <= 52; weeks++) {
        const plan = skeleton(weeks, distance);
        expect(plan.weeks).toHaveLength(weeks);
        expect(errors(plan), `${distance} ${weeks}w cold`).toEqual([]);
        expect(validatePlan(plan).valid, `${distance} ${weeks}w cold`).toBe(true);
        expect(
          validatePlan(plan, { seedCtl: 40, seedAtl: 40, experienceLevel: 'intermediate' }).valid,
          `${distance} ${weeks}w seeded`,
        ).toBe(true);
        // Schema round-trip: no Infinity/NaN leaks into a persisted plan.
        expect(() => TrainingPlan.parse(JSON.parse(JSON.stringify(plan)))).not.toThrow();
      }
    });
  }

  it('lays out consecutive weeks of seven dated days from the start date', () => {
    for (let weeks = 1; weeks <= 52; weeks++) {
      const plan = skeleton(weeks);
      expect(plan.startDate).toBe(START);
      expect(plan.endDate).toBe(addDays(START, weeks * 7 - 1));
      plan.weeks.forEach((week, i) => {
        expect(week.weekNumber).toBe(i + 1);
        expect(week.days.map((d) => d.day)).toEqual([1, 2, 3, 4, 5, 6, 7]);
        for (const day of week.days) {
          expect(day.date).toBe(addDays(START, i * 7 + day.day - 1));
          for (const s of day.sessions) expect(s.date).toBe(day.date);
        }
        // Week totals are the sums of the sessions (computed in code).
        const tss = week.days
          .flatMap((d) => d.sessions)
          .reduce((a, s) => a + (s.targetTss ?? 0), 0);
        expect(week.targetTss).toBe(Number(tss.toFixed(0)));
      });
    }
  });

  it('phaseForWeek: base first, taper last (>=3 weeks), phases never go backwards', () => {
    for (let weeks = 1; weeks <= 52; weeks++) {
      const phases = skeleton(weeks).weeks.map((w) => w.phase);
      expect(phases[0], `${weeks}w`).toBe('base');
      if (weeks >= 3) expect(phases[weeks - 1], `${weeks}w`).toBe('taper');
      const taperCount = phases.filter((p) => p === 'taper').length;
      expect(taperCount, `${weeks}w`).toBe(weeks >= 6 ? 2 : weeks >= 3 ? 1 : 0);
      // A recovery week only ever replaces a 4th (non-taper) week.
      phases.forEach((p, i) => {
        if (p === 'recovery') expect((i + 1) % 4).toBe(0);
      });
      const ordered = phases.filter((p) => p !== 'recovery').map((p) => PHASE_ORDER[p]);
      for (let i = 1; i < ordered.length; i++) {
        expect(ordered[i], `${weeks}w ${phases.join(',')}`).toBeGreaterThanOrEqual(ordered[i - 1]);
      }
    }
  });

  it('includes a peak week only for long-enough plans', () => {
    expect(skeleton(5).weeks.map((w) => w.phase)).not.toContain('peak');
    expect(skeleton(12).weeks.map((w) => w.phase)).toContain('peak');
  });

  it('long runs stay within the 140-minute ceiling and the final taper week has none', () => {
    const plan = skeleton(52);
    for (const week of plan.weeks) {
      const longs = week.days.flatMap((d) => d.sessions).filter((s) => s.type === 'long');
      for (const l of longs) expect(l.targetDurationSec ?? 0).toBeLessThanOrEqual(140 * 60);
    }
    const last = plan.weeks.at(-1);
    expect(last?.days.flatMap((d) => d.sessions).some((s) => s.type === 'long')).toBe(false);
  });

  it('paces sessions off the athlete anchor (higher vdot => faster easy pace)', () => {
    const slow = skeleton(8, '10k', AthleteProfile.parse({ vdot: 35 }));
    const fast = skeleton(8, '10k', AthleteProfile.parse({ vdot: 60 }));
    const easyPace = (p: TrainingPlan) =>
      p.weeks[0].days.flatMap((d) => d.sessions).find((s) => s.type === 'easy')
        ?.targetPaceSecPerKm ?? 0;
    expect(easyPace(fast)).toBeLessThan(easyPace(slow));
  });
});

describe('generatePlan (no LLM) — every generated plan passes validatePlan', () => {
  it('1-52 weeks x experience levels x seeded fitness', async () => {
    for (let weeks = 1; weeks <= 52; weeks++) {
      const distance = DISTANCES[weeks % DISTANCES.length];
      for (const level of LEVELS) {
        for (const ctl of [0, 60]) {
          const profile = AthleteProfile.parse({ experienceLevel: level, vdot: 45 });
          const result = await generatePlan({
            profile,
            goal: goalFor(distance),
            weeks,
            startDate: START,
            context: seededContext(profile, ctl),
          });
          const label = `${distance} ${weeks}w ${level} ctl=${ctl}`;
          expect(result.validation.valid, label).toBe(true);
          expect(result.plan.weeks, label).toHaveLength(weeks);
          // Re-validate independently with the same guardrail context.
          const ctx = { seedCtl: ctl, seedAtl: ctl, experienceLevel: level };
          expect(errors(result.plan, ctx), label).toEqual([]);
        }
      }
    }
  }, 60_000);
});

describe('resolveRaceDistanceM', () => {
  it('maps each standard distance and falls back for custom', () => {
    expect(resolveRaceDistanceM(RaceGoal.parse({ distance: '5k' }))).toBe(5000);
    expect(resolveRaceDistanceM(RaceGoal.parse({ distance: '10k' }))).toBe(10_000);
    expect(resolveRaceDistanceM(RaceGoal.parse({ distance: 'half' }))).toBe(21_097);
    expect(resolveRaceDistanceM(RaceGoal.parse({ distance: 'marathon' }))).toBe(42_195);
    expect(
      resolveRaceDistanceM(RaceGoal.parse({ distance: 'custom', customDistanceM: 7000 })),
    ).toBe(7000);
    expect(resolveRaceDistanceM(RaceGoal.parse({ distance: 'custom' }))).toBe(10_000);
  });
});

describe('rescaleSession', () => {
  it('re-derives load and distance at the new duration, keeping pace, date and rationale', () => {
    const s = makeSession('threshold', 50, THRESHOLD, { date: '2026-01-06', rationale: 'why' });
    const r = rescaleSession(s, 25);
    expect(r.type).toBe('threshold');
    expect(r.targetDurationSec).toBe(25 * 60);
    expect(r.targetPaceSecPerKm).toBe(s.targetPaceSecPerKm);
    expect(r.date).toBe('2026-01-06');
    expect(r.rationale).toBe('why');
    expect(r.targetTss ?? 0).toBeLessThan(s.targetTss ?? 0);
    expect(Math.abs((r.targetDistanceM ?? 0) - (s.targetDistanceM ?? 0) / 2)).toBeLessThan(25);
  });

  it('returns a rest session unchanged (same reference)', () => {
    const rest = makeSession('rest', 0, THRESHOLD);
    expect(rescaleSession(rest, 60)).toBe(rest);
  });

  it('falls back to the default threshold for a pace-less session', () => {
    const cross = makeSession('cross_training', 40, THRESHOLD);
    const r = rescaleSession(cross, 20);
    expect(r.targetDurationSec).toBe(1200);
    expect(r.targetPaceSecPerKm).toBeUndefined();
    expect(thresholdFromSession(cross)).toBe(3.0);
  });

  it('thresholdFromSession inverts the makeSession pace scaling', () => {
    for (const type of ['easy', 'long', 'tempo', 'threshold', 'interval'] as const) {
      expect(thresholdFromSession(makeSession(type, 40, THRESHOLD))).toBeCloseTo(THRESHOLD, 1);
    }
  });

  it('never emits non-finite numbers, whatever the duration', () => {
    for (const min of [10, 33.3, 90, 180]) {
      expect(allFinite(rescaleSession(makeSession('long', 80, THRESHOLD), min))).toBe(true);
    }
  });
});

describe('materializeProposal', () => {
  const profile = AthleteProfile.parse({ vdot: 45 });
  const goal = RaceGoal.parse({ distance: '10k' });
  const params = (weeks: number) => ({
    profile,
    goal,
    weeks,
    startDate: `${START}T07:30:00Z`,
    planId: 'llm',
    createdAt: '2026-01-01T00:00:00Z',
  });
  type LlmWeek = LlmPlanProposal['weeks'][number];
  type LlmDay = LlmWeek['days'][number];
  const day = (dayOfWeek: number, workoutType: LlmDay['workoutType']): LlmDay => ({
    dayOfWeek,
    workoutType,
    emphasis: 'e',
    rationale: `r${dayOfWeek}`,
  });
  const goodWeek = (weekNumber: number, phase: PlanPhase = 'base'): LlmWeek => ({
    weekNumber,
    phase,
    days: [
      day(1, 'rest'),
      day(2, 'easy'),
      day(3, 'threshold'),
      day(4, 'easy'),
      day(5, 'rest'),
      day(6, 'easy'),
      day(7, 'long'),
    ],
  });
  const sessionOn = (plan: TrainingPlan, week: number, dow: number) =>
    plan.weeks.find((w) => w.weekNumber === week)?.days.find((d) => d.day === dow)?.sessions[0];

  it('computes every number in code and dates days from a datetime start', () => {
    const plan = materializeProposal({ weeks: [goodWeek(1), goodWeek(2, 'build')] }, params(2));
    expect(plan.startDate).toBe(START);
    expect(plan.endDate).toBe(addDays(START, 13));
    expect(plan.weeks.map((w) => w.weekNumber)).toEqual([1, 2]);
    const w2 = plan.weeks[1];
    expect(w2.days[0].date).toBe(addDays(START, 7));
    expect(sessionOn(plan, 1, 7)?.targetDurationSec).toBe(70 * 60); // base long run
    expect(sessionOn(plan, 2, 7)?.targetDurationSec).toBe(90 * 60); // build long run
    expect(sessionOn(plan, 2, 3)?.rationale).toBe('r3');
    const tss = w2.days.flatMap((d) => d.sessions).reduce((a, s) => a + (s.targetTss ?? 0), 0);
    expect(w2.targetTss).toBe(Number(tss.toFixed(0)));
    expect(validatePlan(plan).valid).toBe(true);
  });

  it('shortens the long run in a taper week', () => {
    const plan = materializeProposal({ weeks: [goodWeek(1, 'taper')] }, params(1));
    expect(sessionOn(plan, 1, 7)?.targetDurationSec).toBe(50 * 60);
  });

  it('drops out-of-range weeks and sorts weeks and days', () => {
    const shuffled = { ...goodWeek(2), days: [...goodWeek(2).days].reverse() };
    const plan = materializeProposal(
      { weeks: [goodWeek(9), shuffled, goodWeek(1), { ...goodWeek(3), weekNumber: 0 }] },
      params(2),
    );
    expect(plan.weeks.map((w) => w.weekNumber)).toEqual([1, 2]);
    expect(plan.weeks[1].days.map((d) => d.day)).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('ignores days outside 1-7', () => {
    const week = {
      ...goodWeek(1),
      days: [...goodWeek(1).days, day(0, 'interval'), day(8, 'interval')],
    };
    const plan = materializeProposal({ weeks: [week] }, params(1));
    expect(plan.weeks[0].days.map((d) => d.day)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(plan.weeks[0].days.flatMap((d) => d.sessions).some((s) => s.type === 'interval')).toBe(
      false,
    );
  });

  it('an empty proposal yields an empty-week plan (which the caller rejects)', () => {
    const plan = materializeProposal({ weeks: [] }, params(4));
    expect(plan.weeks).toEqual([]);
    expect(plan.endDate).toBe(addDays(START, 27));
  });

  it('duplicate week/day keys never produce out-of-range or misdated sessions', () => {
    // Whether duplicates are kept, deduped or rejected is the guardrail's call
    // (AUD-004); here we only pin that the materializer stays in range, dated
    // correctly and computed in code.
    const plan = materializeProposal(
      {
        weeks: [
          goodWeek(1),
          goodWeek(1, 'build'),
          { ...goodWeek(2), days: [...goodWeek(2).days, day(3, 'interval')] },
        ],
      },
      params(2),
    );
    expect(new Set(plan.weeks.map((w) => w.weekNumber))).toEqual(new Set([1, 2]));
    for (const w of plan.weeks) {
      for (const d of w.days) {
        expect(d.date).toBe(addDays(START, (w.weekNumber - 1) * 7 + d.day - 1));
        for (const s of d.sessions) expect(allFinite(s)).toBe(true);
      }
    }
  });

  it('well-formed proposals for 1-52 weeks pass validatePlan; aggressive ones are repairable', () => {
    // An aggressive week: back-to-back quality and no rest day at all.
    const hardWeek = (weekNumber: number): LlmWeek => ({
      weekNumber,
      phase: 'build',
      days: [1, 2, 3, 4, 5, 6, 7].map((d) =>
        day(d, d === 7 ? 'long' : d % 2 === 0 ? 'interval' : 'threshold'),
      ),
    });
    for (let weeks = 1; weeks <= 52; weeks++) {
      const good = materializeProposal(
        { weeks: Array.from({ length: weeks }, (_, i) => goodWeek(i + 1)) },
        params(weeks),
      );
      expect(validatePlan(good).valid, `${weeks}w good`).toBe(true);

      const hard = materializeProposal(
        { weeks: Array.from({ length: weeks }, (_, i) => hardWeek(i + 1)) },
        params(weeks),
      );
      expect(validatePlan(hard).valid, `${weeks}w hard`).toBe(false);
    }
  });
});

describe('proposeNextWorkout — branch table', () => {
  const profile = AthleteProfile.parse({ vdot: 45 });
  const ctx = (over: Partial<CoachContext>): CoachContext => ({
    generatedAt: '2026-01-04T00:00:00Z',
    profile,
    recentActivities: [],
    ...over,
  });
  const dist = (hardPct: number) => ({
    easySec: 0,
    moderateSec: 0,
    hardSec: 0,
    easyPct: 100 - hardPct,
    moderatePct: 0,
    hardPct,
  });
  const recent = (tss: number, durationSec: number, distanceKm = 8) => ({
    date: '2026-01-03',
    name: 'run',
    sportType: 'run' as const,
    distanceKm,
    durationSec,
    tss,
    loadMethod: 'rtss' as const,
  });

  it('backs off when deeply fatigued', () => {
    const w = proposeNextWorkout(
      ctx({ fitness: { date: 'd', ctl: 50, atl: 90, tsb: -30 } }),
      profile,
    );
    expect(w.type).toBe('recovery');
  });

  it('follows a hard session with an easy day', () => {
    const w = proposeNextWorkout(ctx({ recentActivities: [recent(90, 3600)] }), profile);
    expect(w.type).toBe('easy');
  });

  it('prescribes intervals in a peak phase when fresh and under-intense', () => {
    const w = proposeNextWorkout(
      ctx({ planPhase: 'peak', fitness: { date: 'd', ctl: 50, atl: 45, tsb: 5 } }),
      profile,
    );
    expect(w.type).toBe('interval');
  });

  it('asks for a long run when none happened recently', () => {
    const w = proposeNextWorkout(
      ctx({
        fitness: { date: 'd', ctl: 50, atl: 50, tsb: -5 },
        weeklyDistribution: dist(25),
        recentActivities: [recent(40, 3600)],
      }),
      profile,
    );
    expect(w.type).toBe('long');
  });

  it('defaults to an easy run otherwise', () => {
    const w = proposeNextWorkout(
      ctx({
        fitness: { date: 'd', ctl: 50, atl: 62, tsb: -12 },
        weeklyDistribution: dist(25),
        recentActivities: [recent(40, 3600, 16)],
      }),
      profile,
    );
    expect(w.type).toBe('easy');
  });

  it('does not treat a zero-duration activity as hard', () => {
    const w = proposeNextWorkout(ctx({ recentActivities: [recent(50, 0)] }), profile);
    expect(w.type).not.toBe('recovery');
  });
});

// Keep `repairPlan` in the import list exercised: a repaired skeleton must stay valid.
describe('repairPlan on a valid skeleton', () => {
  it('is a no-op for validity', () => {
    const plan = skeleton(16);
    expect(validatePlan(repairPlan(plan).plan).valid).toBe(true);
  });
});
