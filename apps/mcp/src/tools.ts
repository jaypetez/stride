import {
  analyzeWorkout,
  buildAcwrSeries,
  buildCoachContext,
  buildPmcSeries,
  computeActivityMetrics,
  computeZones,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  generatePlan,
  latestAcwr,
  latestPmc,
  rampRatePerWeek,
  resolveNowIso,
  suggestNextWorkout,
  toDailyLoads,
} from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  type DailyLoad,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import type { McpState } from './state';

export interface ToolResult {
  text: string;
  data: unknown;
}

interface Ctx {
  profile: AthleteProfile;
  activities: Activity[];
  goal?: RaceGoalType;
  /** Durable daily-load series for live data; undefined in demo mode. */
  dailyLoads?: DailyLoad[];
}

async function loadCtx(state: McpState, demo: boolean): Promise<Ctx> {
  if (demo) {
    return {
      profile: DEMO_PROFILE,
      activities: [...demoHistory(), demoActivity()],
      goal: RaceGoal.parse({ distance: '10k', date: '2026-09-06', name: '10k' }),
    };
  }
  const { AthleteProfile } = await import('@stride/schemas');
  const profile = (await state.store.loadProfile()) ?? AthleteProfile.parse({});
  const activities = await state.store.loadActivities();
  const goal = (await state.store.loadGoal()) ?? undefined;
  const dailyLoads = await state.store.loadDailyLoads();
  return { profile, activities, goal, dailyLoads };
}

const deps = (state: McpState) => ({
  llm: state.llm,
  models: state.config.models,
  nowIso: state.config.now ? () => resolveNowIso(state.config) : undefined,
});

export async function toolTrainingLoad(state: McpState, demo: boolean): Promise<ToolResult> {
  const { profile, activities, dailyLoads } = await loadCtx(state, demo);
  const dailies = dailyLoads ?? toDailyLoads(activities, profile);
  const pmc = buildPmcSeries(dailies);
  const acwr = buildAcwrSeries(dailies);
  const latest = latestPmc(pmc) ?? null;
  const la = latestAcwr(acwr) ?? null;
  const ramp = rampRatePerWeek(pmc, 2);
  const text = latest
    ? `Fitness (CTL) ${latest.ctl}, Fatigue (ATL) ${latest.atl}, Form (TSB) ${latest.tsb}. ACWR ${la?.acwr ?? '—'} (${la?.flag ?? 'n/a'}). Ramp ${ramp}/week.`
    : 'No training-load data available (sync activities first, or pass demo=true).';
  return { text, data: { fitness: latest, acwr: la, rampRatePerWeek: ramp } };
}

export async function toolRecentActivities(
  state: McpState,
  demo: boolean,
  limit = 10,
): Promise<ToolResult> {
  const { activities } = await loadCtx(state, demo);
  const summaries = activities
    .sort((a, b) => b.startDate.localeCompare(a.startDate))
    .slice(0, limit)
    .map(({ streams, ...rest }) => rest);
  return {
    text: `${summaries.length} recent activities.`,
    data: summaries,
  };
}

export async function toolZones(state: McpState, demo: boolean): Promise<ToolResult> {
  const { profile } = await loadCtx(state, demo);
  const zones = computeZones(profile);
  return { text: `${zones.hr.length} HR zones, ${zones.pace.length} pace zones.`, data: zones };
}

export async function toolAnalyze(
  state: McpState,
  demo: boolean,
  id?: string,
): Promise<ToolResult> {
  const { profile, activities, goal, dailyLoads } = await loadCtx(state, demo);
  const activity =
    demo || !id || id === 'last'
      ? [...activities].sort((a, b) => b.startDate.localeCompare(a.startDate))[0]
      : activities.find((a) => a.id === id);
  if (!activity) return { text: `Activity "${id}" not found.`, data: null };
  const context = buildCoachContext({
    activities,
    profile,
    goal,
    asOfDate: activity.startDate,
    dailyLoads,
  });
  const metrics = computeActivityMetrics(activity, profile);
  const analysis = await analyzeWorkout({ activity, profile, context, deps: deps(state) });
  return { text: analysis.headline, data: { metrics, analysis } };
}

export async function toolNext(state: McpState, demo: boolean): Promise<ToolResult> {
  const { profile, activities, goal, dailyLoads } = await loadCtx(state, demo);
  const context = buildCoachContext({
    activities,
    profile,
    goal,
    asOfDate: resolveNowIso(state.config),
    dailyLoads,
  });
  const workout = await suggestNextWorkout({ context, profile, deps: deps(state) });
  return { text: `${workout.title}: ${workout.rationale}`, data: workout };
}

export async function toolPlan(
  state: McpState,
  args: { demo: boolean; race?: string; weeks?: number; start?: string; date?: string },
): Promise<ToolResult> {
  const { profile, activities, goal: storedGoal, dailyLoads } = await loadCtx(state, args.demo);
  const distance = args.race ?? storedGoal?.distance ?? '10k';
  const goal = RaceGoal.parse({
    distance,
    name: storedGoal?.name ?? distance,
    date: args.date ?? storedGoal?.date,
  });
  const weeks = args.weeks ?? 8;
  const startDate = args.start ?? resolveNowIso(state.config).slice(0, 10);
  const context = buildCoachContext({ activities, profile, goal, dailyLoads });
  const { plan, validation } = await generatePlan({
    profile,
    goal,
    weeks,
    startDate,
    context,
    deps: deps(state),
  });
  return {
    text: `${weeks}-week ${goal.name ?? goal.distance} plan (${validation.valid ? 'valid' : `${validation.violations.length} guardrail issues`}).`,
    data: { plan, validation },
  };
}
