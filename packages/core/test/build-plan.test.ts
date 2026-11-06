import { AthleteProfile, type FitnessSeed, RaceGoal, TrainingPlan } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  BUILD_MAX_WEEKS,
  BUILD_MIN_WEEKS,
  buildCoachContext,
  buildPlanSkeleton,
  clampBuildWeeks,
  generatePlan,
  SEED_MIN_WEEKLY_MIN,
  validatePlan,
} from '../src/coach/index';
import { DEMO_PROFILE, demoHistory, demoNowIso } from '../src/fixtures';
import { seedSummary } from '../src/science/index';

/** MVP-026: the no-race "build my fitness" plan kind. */

const PROFILE = AthleteProfile.parse(DEMO_PROFILE);
const START = '2026-07-13';

function build(
  seed: FitnessSeed | null | undefined,
  weeks: number,
  goal: RaceGoal = RaceGoal.parse({ distance: '10k' }),
): TrainingPlan {
  return buildPlanSkeleton({
    profile: PROFILE,
    goal,
    weeks,
    startDate: START,
    planId: 'b',
    createdAt: `${START}T00:00:00Z`,
    seed,
    kind: 'build',
  });
}

function weekMinutes(plan: TrainingPlan, i: number): number {
  return plan.weeks[i].days.reduce(
    (sum, d) => sum + d.sessions.reduce((a, s) => a + (s.targetDurationSec ?? 0) / 60, 0),
    0,
  );
}

const seed = (weeklyMinutes: number, longestRunMin: number, vdot: number | null = null) => ({
  weeklyMinutes,
  longestRunMin,
  vdot,
  runCount: 12,
});

const SEEDS: Array<[string, FitnessSeed | null]> = [
  ['no seed (template fallback)', null],
  ['very low (below the floor)', seed(30, 15)],
  ['low-volume beginner', seed(75, 30)],
  ['beginner with a long run', seed(90, 45, 32)],
  ['medium', seed(180, 60, 45)],
  ['medium, short long run', seed(240, 35, 48)],
  ['high', seed(420, 120, 55)],
  ['very high', seed(700, 150, 62)],
  ['long-run heavy', seed(120, 110, 40)],
];

describe('build plans (MVP-026)', () => {
  it('are marked kind build, with no race, peak or taper', () => {
    const plan = build(seed(180, 60), 8);
    expect(TrainingPlan.parse(plan)).toEqual(plan);
    expect(plan.kind).toBe('build');
    expect(plan.weeks).toHaveLength(8);
    expect(plan.summary).toContain('Fitness build, 8 weeks');
    const phases = plan.weeks.map((w) => w.phase);
    expect(phases).toEqual([
      'base',
      'base',
      'base',
      'recovery',
      'build',
      'build',
      'build',
      'recovery',
    ]);
    const types = plan.weeks.flatMap((w) => w.days.flatMap((d) => d.sessions.map((s) => s.type)));
    expect(types).not.toContain('race');
  });

  it('ignores a goal race date (no alignment) and keeps the requested start', () => {
    const goal = RaceGoal.parse({ distance: '10k', date: '2026-08-01' });
    const plan = build(null, 10, goal);
    expect(plan.startDate).toBe(START);
    expect(plan.weeks).toHaveLength(10);
  });

  it('clamps the length to 4..16 weeks', () => {
    expect(clampBuildWeeks(1)).toBe(BUILD_MIN_WEEKS);
    expect(clampBuildWeeks(40)).toBe(BUILD_MAX_WEEKS);
    expect(clampBuildWeeks(Number.NaN)).toBe(BUILD_MIN_WEEKS);
    expect(build(null, 2).weeks).toHaveLength(4);
    expect(build(null, 30).weeks).toHaveLength(16);
  });

  it('race plans are unchanged and carry no kind', () => {
    const race = buildPlanSkeleton({
      profile: PROFILE,
      goal: RaceGoal.parse({ distance: '10k' }),
      weeks: 8,
      startDate: START,
      planId: 'r',
      createdAt: START,
    });
    expect(race.kind).toBeUndefined();
    expect(race.weeks.some((w) => w.phase === 'taper')).toBe(true);
  });
});

describe('build plans always pass the guardrail (property)', () => {
  for (const [name, s] of SEEDS) {
    it(`${name}: weeks 4..16`, () => {
      for (let weeks = BUILD_MIN_WEEKS; weeks <= BUILD_MAX_WEEKS; weeks++) {
        const plan = build(s, weeks);
        const where = `${name} ${weeks}w`;
        expect(validatePlan(plan).violations, where).toEqual([]);
        expect(plan.weeks, where).toHaveLength(weeks);
        for (const w of plan.weeks) {
          expect(['base', 'build', 'recovery'], where).toContain(w.phase);
          expect(w.phase === 'recovery', `${where} week ${w.weekNumber}`).toBe(
            w.weekNumber % 4 === 0,
          );
          for (const d of w.days) {
            for (const x of d.sessions) expect(x.type, where).not.toBe('race');
          }
        }

        if (s) {
          const start = Math.max(SEED_MIN_WEEKLY_MIN, s.weeklyMinutes);
          expect(Math.abs(weekMinutes(plan, 0) / start - 1), where).toBeLessThanOrEqual(0.1);
        }
        // Loading weeks grow at most 10%; a recovery week is ~0.65x the last loading week.
        let prev: number | undefined;
        plan.weeks.forEach((w, i) => {
          const m = weekMinutes(plan, i);
          if (w.phase === 'recovery') {
            if (s && prev !== undefined) expect(m / prev, where).toBeCloseTo(0.65, 1);
            return;
          }
          if (s && prev !== undefined) {
            expect(m / prev, `${where} week ${i + 1}`).toBeLessThanOrEqual(1.1);
          }
          prev = m;
        });
      }
    });
  }
});

describe('generatePlan with kind build', () => {
  it('builds a seeded, valid fitness block and returns the seed', async () => {
    const asOfDate = demoNowIso();
    const context = buildCoachContext({ activities: demoHistory(), profile: PROFILE, asOfDate });
    const result = await generatePlan({
      profile: PROFILE,
      goal: RaceGoal.parse({ distance: '10k' }),
      weeks: 6,
      kind: 'build',
      startDate: START,
      context,
      deps: { nowIso: () => asOfDate },
    });
    expect(result.plan.kind).toBe('build');
    expect(result.plan.id).toBe(`plan-${START}-6w-build`);
    expect(result.validation.valid).toBe(true);
    expect(result.seed).toEqual(context.fitnessSeed);
    expect(result.plan.weeks.map((w) => w.phase)).not.toContain('taper');
  });

  it('clamps out-of-range weeks', async () => {
    const result = await generatePlan({
      profile: PROFILE,
      goal: RaceGoal.parse({ distance: '10k' }),
      weeks: 52,
      kind: 'build',
      startDate: START,
    });
    expect(result.plan.weeks).toHaveLength(16);
  });
});

describe('seedSummary', () => {
  it('formats hours per week and the longest run', () => {
    expect(seedSummary(seed(192, 74.6))).toBe(
      'Based on your last 4 weeks: 3.2 h/week, longest run 75 min',
    );
  });
});
