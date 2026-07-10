import { buildCoachContext, DEMO_PROFILE, demoHistory, generatePlan } from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import { coachDeps, getProfile, loadApp, todayKey } from '../app';
import { dim, errorMsg, printDisclaimer, printPlan, success } from '../ui';

const RACE_CHOICES = ['5k', '10k', 'half', 'marathon'];

export async function planCommand(opts: {
  demo?: boolean;
  race?: string;
  weeks?: string;
  start?: string;
  date?: string;
  json?: boolean;
}): Promise<void> {
  const app = loadApp();

  let profile: AthleteProfile = DEMO_PROFILE;
  let activities: Activity[];
  let storedGoal: RaceGoalType | null = null;

  if (opts.demo) {
    activities = demoHistory();
  } else {
    profile = await getProfile(app.store);
    activities = await app.store.loadActivities();
    storedGoal = await app.store.loadGoal();
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
  const weeks = opts.weeks ? Math.max(1, Math.min(52, Number(opts.weeks))) : 8;
  const startDate = opts.start ?? todayKey(app.config);

  const context = buildCoachContext({ activities, profile, goal });
  const { plan, validation } = await generatePlan({
    profile,
    goal,
    weeks,
    startDate,
    context,
    deps: coachDeps(app),
  });

  if (!opts.demo) {
    await app.store.savePlan(plan);
    await app.store.saveGoal(goal);
  }

  if (opts.json) {
    console.log(JSON.stringify({ plan, validation }, null, 2));
    return;
  }

  if (!opts.demo) success(`Saved plan to ${app.store.dir}`);
  printPlan(plan, validation);
  printDisclaimer();
  if (!app.llm) dim('\n  (Set ANTHROPIC_API_KEY for an LLM-written plan overview.)');
}
