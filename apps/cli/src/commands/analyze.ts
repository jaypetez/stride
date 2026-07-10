import {
  analyzeWorkout,
  buildCoachContext,
  computeActivityMetrics,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
} from '@stride/core';
import { type Activity, RaceGoal, type RaceGoal as RaceGoalType } from '@stride/schemas';
import { coachDeps, getProfile, loadApp, mostRecent } from '../app';
import {
  dim,
  errorMsg,
  heading,
  printAttribution,
  printDisclaimer,
  printFlags,
  printMetrics,
} from '../ui';

export async function analyzeCommand(opts: {
  demo?: boolean;
  id?: string;
  json?: boolean;
}): Promise<void> {
  const app = loadApp();

  let activity: Activity | undefined;
  let profile = DEMO_PROFILE;
  let activities: Activity[];
  let goal: RaceGoalType | undefined;

  if (opts.demo) {
    activity = demoActivity();
    activities = [...demoHistory(), activity];
    goal = RaceGoal.parse({ distance: '10k', name: '10k' });
  } else {
    profile = await getProfile(app.store);
    activities = await app.store.loadActivities();
    if (activities.length === 0) {
      errorMsg('No activities stored. Run `stride sync` first, or try `stride analyze --demo`.');
      return;
    }
    activity = opts.id ? activities.find((a) => a.id === opts.id) : mostRecent(activities);
    if (!activity) {
      errorMsg(`Activity "${opts.id}" not found.`);
      return;
    }
    goal = (await app.store.loadGoal()) ?? undefined;
  }

  const context = buildCoachContext({ activities, profile, goal, asOfDate: activity.startDate });
  const metrics = computeActivityMetrics(activity, profile);
  const result = await analyzeWorkout({ activity, profile, context, deps: coachDeps(app) });

  if (opts.json) {
    console.log(JSON.stringify({ metrics, analysis: result }, null, 2));
    return;
  }

  printMetrics(activity, metrics);
  heading('Coach');
  console.log(`  ${result.explanation}`);
  printFlags(result.flags);
  printAttribution(activity);
  printDisclaimer();
  if (!app.llm) dim('\n  (Set ANTHROPIC_API_KEY for richer, LLM-written analysis.)');
}
