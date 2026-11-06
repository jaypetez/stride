import {
  Activity,
  AthleteProfile,
  type FitnessSeed,
  RaceGoal,
  type TrainingPlan,
} from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  buildCoachContext,
  buildPlanSkeleton,
  generatePlan,
  SEED_LONG_FLOOR_MIN,
  SEED_LONG_SHARE,
  SEED_MIN_WEEKLY_MIN,
  validatePlan,
} from '../src/coach/index';
import { DEMO_PROFILE, demoHistory, demoNowIso } from '../src/fixtures';
import { addDays, fitnessSeed, thresholdSpeedFromVdot, vdotFromEffort } from '../src/science/index';

const AS_OF = '2026-07-09';

function run(id: string, day: string, over: Partial<Activity> = {}): Activity {
  return Activity.parse({
    id,
    source: 'upload',
    sportType: 'run',
    name: id,
    startDate: `${day}T06:00:00Z`,
    distance: 8000,
    movingTime: 2400,
    elapsedTime: 2500,
    ...over,
  });
}

describe('fitnessSeed', () => {
  it('returns null with fewer than 3 runs in the 28-day window', () => {
    expect(fitnessSeed([], AS_OF)).toBeNull();
    const two = [run('a', AS_OF), run('b', addDays(AS_OF, -3))];
    expect(fitnessSeed(two, AS_OF)).toBeNull();
    // A third run 28 days back is outside the window.
    expect(fitnessSeed([...two, run('c', addDays(AS_OF, -28))], AS_OF)).toBeNull();
    // Non-runs never count.
    expect(fitnessSeed([...two, run('d', AS_OF, { sportType: 'walk' })], AS_OF)).toBeNull();
  });

  it('averages 4 weeks of running minutes and finds the longest run', () => {
    const acts = [
      run('a', AS_OF, { movingTime: 1800 }),
      run('b', addDays(AS_OF, -27), { movingTime: 5400, distance: 15000 }), // first day in window
      run('c', addDays(AS_OF, -10), { movingTime: 3000, manual: true }), // manual still counts for volume
      run('d', addDays(AS_OF, -28), { movingTime: 9999 }), // outside
      run('e', addDays(AS_OF, 1), { movingTime: 9999 }), // after asOf
      run('f', addDays(AS_OF, -2), { sportType: 'walk', movingTime: 9999 }),
    ];
    const seed = fitnessSeed(acts, `${AS_OF}T12:00:00Z`);
    expect(seed).toEqual({
      weeklyMinutes: Number(((1800 + 5400 + 3000) / 60 / 4).toFixed(1)),
      longestRunMin: 90,
      vdot: Number(vdotFromEffort(8000, 1800).toFixed(1)), // run 'a' is the fastest
      runCount: 3,
    });
  });

  it('uses the local start day when present', () => {
    const late = run('x', addDays(AS_OF, -1), {
      startDate: `${addDays(AS_OF, -28)}T23:30:00Z`,
      startDateLocal: `${addDays(AS_OF, -27)}T01:30:00`,
    });
    const seed = fitnessSeed([late, run('a', AS_OF), run('b', AS_OF)], AS_OF);
    expect(seed?.runCount).toBe(3);
  });

  it('takes VDOT from the best qualifying run in the last 56 days', () => {
    const fast = run('fast', addDays(AS_OF, -50), { distance: 10000, movingTime: 2400 });
    const tooShort = run('short', AS_OF, { distance: 2500, movingTime: 600 });
    const treadmill = run('tm', AS_OF, { distance: 12000, movingTime: 2400, trainer: true });
    const old = run('old', addDays(AS_OF, -56), { distance: 10000, movingTime: 2000 });
    const base = [run('a', AS_OF), run('b', AS_OF), run('c', AS_OF)];
    const seed = fitnessSeed([...base, fast, tooShort, treadmill, old], AS_OF);
    expect(seed?.vdot).toBe(Number(vdotFromEffort(10000, 2400).toFixed(1)));
    expect(seed?.runCount).toBe(5); // base + tooShort + treadmill (fast/old are outside 28 days)

    const noVdot = fitnessSeed(
      [run('a', AS_OF, { distance: 2000 }), run('b', AS_OF, { distance: 2000 }), tooShort],
      AS_OF,
    );
    expect(noVdot?.vdot).toBeNull();
  });
});

// --- seeded plan skeleton ---

const PROFILE = AthleteProfile.parse(DEMO_PROFILE);
const START = '2026-07-13';

function skeleton(
  seed: FitnessSeed | undefined,
  weeks: number,
  goal: RaceGoal = RaceGoal.parse({ distance: '10k' }),
  profile: AthleteProfile = PROFILE,
): TrainingPlan {
  return buildPlanSkeleton({
    profile,
    goal,
    weeks,
    startDate: START,
    planId: 'p',
    createdAt: `${START}T00:00:00Z`,
    seed,
  });
}

function weekMinutes(plan: TrainingPlan, i: number): number {
  return plan.weeks[i].days.reduce(
    (sum, d) =>
      sum +
      d.sessions
        .filter((s) => s.type !== 'race')
        .reduce((a, s) => a + (s.targetDurationSec ?? 0) / 60, 0),
    0,
  );
}

function longRunMin(plan: TrainingPlan, i: number): number {
  const long = plan.weeks[i].days.flatMap((d) => d.sessions).find((s) => s.type === 'long');
  return (long?.targetDurationSec ?? 0) / 60;
}

const seed = (weeklyMinutes: number, longestRunMin: number, vdot: number | null = null) => ({
  weeklyMinutes,
  longestRunMin,
  vdot,
  runCount: 12,
});

describe('buildPlanSkeleton with a fitness seed (MVP-025)', () => {
  it('is unchanged without a seed (undefined or null)', () => {
    const plain = skeleton(undefined, 12);
    expect(skeleton(null as unknown as undefined, 12)).toEqual(plain);
    expect(weekMinutes(plain, 0)).toBe(45 + 50 + 40 + 40 + 68);
  });

  it('starts week 1 at the recent weekly volume and the recent long run', () => {
    const plan = skeleton(seed(150, 50), 12);
    expect(Math.abs(weekMinutes(plan, 0) - 150)).toBeLessThan(1);
    expect(longRunMin(plan, 0)).toBeCloseTo(50, 1);
  });

  it('clamps the first long run to the floor and to the long-run share', () => {
    expect(longRunMin(skeleton(seed(200, 25), 8), 0)).toBeCloseTo(SEED_LONG_FLOOR_MIN, 1);
    expect(longRunMin(skeleton(seed(100, 90), 8), 0)).toBeCloseTo(SEED_LONG_SHARE * 100, 1);
    // Below the volume floor the plan starts at the floor.
    expect(weekMinutes(skeleton(seed(30, 20), 8), 0)).toBeCloseTo(SEED_MIN_WEEKLY_MIN, 0);
  });

  it('paces from the seed VDOT when present, else from the profile', () => {
    const vdot = 40;
    const easyPace = (p: TrainingPlan) =>
      p.weeks[0].days.flatMap((d) => d.sessions).find((s) => s.type === 'easy')?.targetPaceSecPerKm;
    const seeded = skeleton(seed(150, 50, vdot), 8);
    const expected = Math.round(1000 / (thresholdSpeedFromVdot(vdot) * 0.75));
    expect(easyPace(seeded)).toBe(expected);
    expect(easyPace(skeleton(seed(150, 50), 8))).toBe(easyPace(skeleton(undefined, 8)));
  });

  it('keeps every 4th week a recovery week and the taper at the end', () => {
    const plan = skeleton(seed(240, 70), 16);
    expect(plan.weeks.filter((w) => w.phase === 'recovery').map((w) => w.weekNumber)).toEqual([
      4, 8, 12,
    ]);
    expect(plan.weeks.slice(-2).every((w) => w.phase === 'taper')).toBe(true);
  });
});

const SEEDS: Array<[string, FitnessSeed]> = [
  ['very low (below the floor)', seed(30, 15)],
  ['low-volume beginner', seed(75, 30)],
  ['beginner with a long run', seed(90, 45, 32)],
  ['medium', seed(180, 60, 45)],
  ['medium, short long run', seed(240, 35, 48)],
  ['high', seed(420, 120, 55)],
  ['very high', seed(700, 150, 62)],
  ['long-run heavy', seed(120, 110, 40)],
];
const DISTANCES = ['5k', '10k', 'half', 'marathon'] as const;

describe('seeded plans always pass the guardrail (property)', () => {
  for (const [name, s] of SEEDS) {
    it(`${name}: weeks 4..24 x race distances, with and without a race date`, () => {
      for (let weeks = 4; weeks <= 24; weeks++) {
        for (const distance of DISTANCES) {
          for (const dated of [false, true]) {
            const goal = RaceGoal.parse({
              distance,
              ...(dated ? { date: addDays(START, weeks * 7 - 1) } : {}),
            });
            const plan = skeleton(s, weeks, goal);
            const where = `${name} ${weeks}w ${distance}${dated ? ' dated' : ''}`;
            const v = validatePlan(plan);
            expect(v.violations, where).toEqual([]);

            // Week 1 matches recent volume (within 10%) above the floor.
            const start = Math.max(SEED_MIN_WEEKLY_MIN, s.weeklyMinutes);
            expect(Math.abs(weekMinutes(plan, 0) / start - 1), where).toBeLessThanOrEqual(0.1);

            // Loading weeks grow at most 10% over the previous loading week.
            let prev: number | undefined;
            plan.weeks.forEach((w, i) => {
              if (w.phase === 'recovery' || w.phase === 'taper') return;
              const m = weekMinutes(plan, i);
              if (prev !== undefined)
                expect(m / prev, `${where} week ${i + 1}`).toBeLessThanOrEqual(1.1);
              prev = m;
              expect(longRunMin(plan, i)).toBeLessThanOrEqual(140);
            });
          }
        }
      }
    });
  }

  it('short plans (1..3 weeks) are valid too', () => {
    for (const [, s] of SEEDS) {
      for (let weeks = 1; weeks <= 3; weeks++) {
        expect(validatePlan(skeleton(s, weeks)).violations).toEqual([]);
      }
    }
  });
});

describe('generatePlan seeds from the coach context', () => {
  it('uses context.fitnessSeed and returns it alongside the plan', async () => {
    const asOfDate = demoNowIso();
    const context = buildCoachContext({
      activities: demoHistory(),
      profile: PROFILE,
      asOfDate,
    });
    expect(context.fitnessSeed).toBeDefined();
    const params = {
      profile: PROFILE,
      goal: RaceGoal.parse({ distance: '10k' }),
      weeks: 8,
      startDate: START,
      deps: { nowIso: () => asOfDate },
    };
    const seeded = await generatePlan({ ...params, context });
    expect(seeded.seed).toEqual(context.fitnessSeed);
    expect(seeded.validation.valid).toBe(true);
    const expected = skeleton(context.fitnessSeed, 8);
    expect(weekMinutes(seeded.plan, 0)).toBeCloseTo(weekMinutes(expected, 0), 5);

    const unseeded = await generatePlan({
      ...params,
      context: { ...context, fitnessSeed: undefined },
    });
    expect(unseeded.seed).toBeUndefined();
    expect(weekMinutes(unseeded.plan, 0)).toBe(243);
  });
});
