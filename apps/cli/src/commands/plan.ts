import { buildCoachContext, DEMO_PROFILE, demoHistory, generatePlan } from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  type DailyLoad,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import { coachDeps, getProfile, loadApp, todayKey } from '../app';
import { dim, errorMsg, printDisclaimer, printFlags, printPlan, success } from '../ui';

const RACE_CHOICES = ['5k', '10k', 'half', 'marathon'];

export async function planCommand(opts: {
  demo?: boolean;
  race?: string;
  weeks?: string;
  start?: string;
  date?: string;
  json?: boolean;
  note?: string;
}): Promise<void> {
  const app = loadApp();

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
    date: opts.date ?? storedGoal?.date,
  });
  // Guard the numeric --weeks input against NaN (e.g. `--weeks abc`) before it
  // reaches the plan engine; clamp valid values to the supported 1–52 range.
  let weeks = 8;
  if (opts.weeks !== undefined) {
    const n = Number(opts.weeks);
    if (!Number.isFinite(n)) {
      errorMsg(`Invalid --weeks "${opts.weeks}"; expected a number between 1 and 52.`);
      return;
    }
    weeks = Math.max(1, Math.min(52, Math.round(n)));
  }
  const startDate = opts.start ?? todayKey(app.config);

  const context = buildCoachContext({ activities, profile, goal, dailyLoads });
  const { plan, validation, disclaimer, flags } = await generatePlan({
    profile,
    goal,
    weeks,
    startDate,
    context,
    note: opts.note,
    deps: coachDeps(app),
  });

  if (!opts.demo) {
    await app.store.savePlan(plan);
    await app.store.saveGoal(goal);
  }

  if (opts.json) {
    console.log(JSON.stringify({ plan, validation, disclaimer, flags }, null, 2));
    return;
  }

  if (!opts.demo) success(`Saved plan to ${app.store.dir}`);
  // Safety flags (esp. a STOP) go first so they can't be missed.
  printFlags(flags);
  printPlan(plan, validation);
  printDisclaimer(disclaimer);
  if (!app.llm) dim('\n  (Set ANTHROPIC_API_KEY for an LLM-written plan overview.)');
}
