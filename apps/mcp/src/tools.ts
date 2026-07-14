import {
  analyzeWorkout,
  buildCoachContext,
  type CoachDataProvider,
  computeActivityMetrics,
  computeZones,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  generatePlan,
  resolveNowIso,
  runCoachTool,
  suggestNextWorkout,
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

/**
 * Build the read-only data provider the SHARED core toolset reads from. MCP and
 * the coach's tool runner both go through this same toolset, so the facts MCP
 * serves are byte-identical to the coach's (GOAL §8).
 */
export async function buildProvider(state: McpState, demo: boolean): Promise<CoachDataProvider> {
  const { profile, activities, goal, dailyLoads } = await loadCtx(state, demo);
  const context = buildCoachContext({
    activities,
    profile,
    goal,
    asOfDate: resolveNowIso(state.config),
    dailyLoads,
  });
  const zones = computeZones(profile);
  return { getContext: () => context, getZones: () => zones };
}

/** Run one of the shared read-only §8 fact tools by name. */
export async function factTool(
  state: McpState,
  name: string,
  args: { demo?: boolean } & Record<string, unknown>,
): Promise<ToolResult> {
  const { demo, ...input } = args;
  const provider = await buildProvider(state, demo ?? false);
  const res = await runCoachTool(provider, name, input);
  return { text: res.summary, data: res.data };
}

export async function toolAnalyze(
  state: McpState,
  demo: boolean,
  id?: string,
  note?: string,
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
  const analysis = await analyzeWorkout({ activity, profile, context, note, deps: deps(state) });
  return { text: analysis.headline, data: { metrics, analysis, disclaimer: analysis.disclaimer } };
}

export async function toolNext(state: McpState, demo: boolean, note?: string): Promise<ToolResult> {
  const { profile, activities, goal, dailyLoads } = await loadCtx(state, demo);
  const context = buildCoachContext({
    activities,
    profile,
    goal,
    asOfDate: resolveNowIso(state.config),
    dailyLoads,
  });
  const workout = await suggestNextWorkout({ context, profile, note, deps: deps(state) });
  return { text: `${workout.title}: ${workout.rationale}`, data: workout };
}

export async function toolPlan(
  state: McpState,
  args: {
    demo: boolean;
    race?: string;
    weeks?: number;
    start?: string;
    date?: string;
    note?: string;
  },
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
  const { plan, validation, disclaimer, flags } = await generatePlan({
    profile,
    goal,
    weeks,
    startDate,
    context,
    note: args.note,
    deps: deps(state),
  });
  return {
    text: `${weeks}-week ${goal.name ?? goal.distance} plan (${validation.valid ? 'valid' : `${validation.violations.length} guardrail issues`}).`,
    data: { plan, validation, disclaimer, flags },
  };
}
