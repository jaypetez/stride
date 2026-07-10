import {
  buildCoachContext,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  suggestNextWorkout,
} from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import { coachDeps, getProfile, loadApp, todayIso } from '../app';
import { dim, errorMsg, heading, printDisclaimer, printWorkout } from '../ui';

export async function nextCommand(opts: { demo?: boolean; json?: boolean }): Promise<void> {
  const app = loadApp();

  let profile: AthleteProfile = DEMO_PROFILE;
  let activities: Activity[];
  let goal: RaceGoalType | undefined;

  if (opts.demo) {
    activities = [...demoHistory(), demoActivity()];
    goal = RaceGoal.parse({ distance: '10k', name: '10k', date: '2026-09-06' });
  } else {
    profile = await getProfile(app.store);
    activities = await app.store.loadActivities();
    if (activities.length === 0) {
      errorMsg('No activities stored. Run `stride sync` first, or try `stride next --demo`.');
      return;
    }
    goal = (await app.store.loadGoal()) ?? undefined;
  }

  const context = buildCoachContext({ activities, profile, goal, asOfDate: todayIso(app.config) });
  const workout = await suggestNextWorkout({ context, profile, deps: coachDeps(app) });

  if (opts.json) {
    console.log(
      JSON.stringify(
        {
          fitness: context.fitness,
          acwr: context.acwr,
          weeklyDistribution: context.weeklyDistribution,
          workout,
        },
        null,
        2,
      ),
    );
    return;
  }

  heading('Current form');
  if (context.fitness) {
    console.log(
      `  CTL ${context.fitness.ctl} (fitness) · ATL ${context.fitness.atl} (fatigue) · TSB ${context.fitness.tsb} (form)`,
    );
  }
  if (context.acwr) console.log(`  ACWR ${context.acwr.acwr} (${context.acwr.flag})`);
  if (context.weeklyDistribution) {
    const d = context.weeklyDistribution;
    console.log(
      `  Last 7 days: ${context.weeklyVolumeKm} km · ${d.easyPct}% easy / ${d.hardPct}% hard`,
    );
  }

  printWorkout(workout);
  printDisclaimer();
  if (!app.llm) dim('\n  (Set ANTHROPIC_API_KEY for an LLM-written rationale.)');
}
