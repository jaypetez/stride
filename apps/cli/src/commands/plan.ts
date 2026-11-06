import {
  BUILD_MAX_WEEKS,
  BUILD_MIN_WEEKS,
  buildCoachContext,
  DEMO_PROFILE,
  demoHistory,
  generatePlan,
  seedSummary,
} from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  type DailyLoad,
  IsoDate,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import { coachDeps, getProfile, loadApp, refNowIso } from '../app';
import { dim, errorMsg, printDisclaimer, printFlags, printPlan, success } from '../ui';

const RACE_CHOICES = ['5k', '10k', 'half', 'marathon'];

export async function planCommand(opts: {
  demo?: boolean;
  /** MVP-026: a no-race "build my fitness" plan (4-16 weeks). */
  build?: boolean;
  race?: string;
  weeks?: string;
  start?: string;
  date?: string;
  json?: boolean;
  note?: string;
}): Promise<void> {
  const app = loadApp();

  // Validate user-supplied dates BEFORE planning: garbage would otherwise
  // become NaN-NaN-NaN dates in a persisted plan.
  for (const [flag, value] of [
    ['--start', opts.start],
    ['--date', opts.date],
  ] as const) {
    if (value !== undefined && !IsoDate.safeParse(value).success) {
      errorMsg(`Invalid ${flag} "${value}"; expected a real date in YYYY-MM-DD format.`);
      return;
    }
  }

  const build = opts.build === true;
  if (build && (opts.race !== undefined || opts.date !== undefined)) {
    errorMsg('A --build plan has no race; drop --race/--date (or omit --build for a race plan).');
    return;
  }

  let profile: AthleteProfile = DEMO_PROFILE;
  let activities: Activity[];
  let storedGoal: RaceGoalType | null = null;
  let dailyLoads: DailyLoad[] | undefined;

  if (opts.demo) {
    activities = demoHistory();
  } else {
    profile = await getProfile(app.store);
    activities = await app.store.loadActivities();
    storedGoal = await app.store.loadGoal();
    dailyLoads = await app.store.loadDailyLoads();
  }

  const distance = opts.race ?? storedGoal?.distance ?? '10k';
  if (opts.race && !RACE_CHOICES.includes(opts.race)) {
    errorMsg(`Unknown race "${opts.race}". Choose one of: ${RACE_CHOICES.join(', ')}.`);
    return;
  }

  const goal = RaceGoal.parse({
    distance,
    name: storedGoal?.name ?? distance,
    // A build plan has no race, so the stored race date is not used.
    date: build ? undefined : (opts.date ?? storedGoal?.date),
  });
  // Guard the numeric --weeks input against NaN (e.g. `--weeks abc`) before it
  // reaches the plan engine; clamp valid values to the supported 1–52 range
  // (a build plan must be 4–16 weeks).
  let weeks = 8;
  if (opts.weeks !== undefined) {
    const n = Number(opts.weeks);
    if (!Number.isFinite(n)) {
      errorMsg(`Invalid --weeks "${opts.weeks}"; expected a number between 1 and 52.`);
      return;
    }
    weeks = Math.max(1, Math.min(52, Math.round(n)));
    if (build && (weeks < BUILD_MIN_WEEKS || weeks > BUILD_MAX_WEEKS)) {
      errorMsg(
        `Invalid --weeks "${opts.weeks}" for --build; a build plan lasts ${BUILD_MIN_WEEKS}-${BUILD_MAX_WEEKS} weeks.`,
      );
      return;
    }
  }
  const asOfDate = refNowIso(app.config, opts.demo);
  const startDate = opts.start ?? asOfDate.slice(0, 10);

  const context = buildCoachContext({ activities, profile, goal, asOfDate, dailyLoads });
  const { plan, validation, disclaimer, flags, seed } = await generatePlan({
    profile,
    goal,
    weeks,
    kind: build ? 'build' : 'race',
    startDate,
    context,
    note: opts.note,
    deps: coachDeps(app),
  });

  // A plan that still breaks a guardrail after repair is never saved.
  if (!validation.valid) {
    if (opts.json) console.log(JSON.stringify({ validation, disclaimer, flags }, null, 2));
    else printPlan(plan, validation);
    errorMsg('Plan failed guardrail validation after repair; it was not saved.');
    return;
  }

  if (!opts.demo) {
    await app.store.savePlan(plan);
    if (!build) await app.store.saveGoal(goal);
  }

  if (opts.json) {
    console.log(JSON.stringify({ plan, validation, disclaimer, flags, seed }, null, 2));
    return;
  }

  if (!opts.demo) success(`Saved plan to ${app.store.dir}`);
  // Safety flags (esp. a STOP) go first so they can't be missed.
  printFlags(flags);
  if (build) {
    console.log(
      seed
        ? `  ${seedSummary(seed)}.`
        : '  Not enough recent runs — starting from a gentle default.',
    );
  }
  printPlan(plan, validation);
  printDisclaimer(disclaimer);
  if (!app.llm) dim('\n  (Set ANTHROPIC_API_KEY for an LLM-written plan overview.)');
}
