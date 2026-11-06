import {
  analyzeWorkout,
  buildCoachContext,
  type CoachDataProvider,
  computeActivityMetrics,
  computeZones,
  DEMO_PROFILE,
  demoActivity,
  demoGoal,
  demoHistory,
  demoNowIso,
  generatePlan,
  resolveNowIso,
  runCoachTool,
  seedSummary,
  suggestNextWorkout,
} from '@stride/core';
import {
  type Activity,
  type AthleteProfile,
  type DailyLoad,
  type PlanKind,
  RaceGoal,
  type RaceGoal as RaceGoalType,
} from '@stride/schemas';
import type { McpState } from './state';

export interface ToolResult {
  text: string;
  data: unknown;
  /** Reported to the MCP client as a tool error (`isError: true`). */
  isError?: boolean;
}

interface Ctx {
  profile: AthleteProfile;
  activities: Activity[];
  goal?: RaceGoalType;
  /** Durable daily-load series for live data; undefined in demo mode. */
  dailyLoads?: DailyLoad[];
}

/**
 * The reference clock for a request: in demo mode the demo "today" (anchored to
 * the bundled history unless STRIDE_NOW pins it), else STRIDE_NOW / real now.
 */
function refNowIso(state: McpState, demo: boolean): string {
  return demo ? demoNowIso(state.config.now) : resolveNowIso(state.config);
}

async function loadCtx(state: McpState, demo: boolean): Promise<Ctx> {
  if (demo) {
    return {
      profile: DEMO_PROFILE,
      activities: [...demoHistory(), demoActivity()],
      goal: demoGoal(refNowIso(state, true)),
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
    asOfDate: refNowIso(state, demo),
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
    // The athlete's LOCAL day of the run (day keys use startDateLocal).
    asOfDate: activity.startDateLocal ?? activity.startDate,
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
    asOfDate: refNowIso(state, demo),
    dailyLoads,
  });
  const workout = await suggestNextWorkout({ context, profile, note, deps: deps(state) });
  return { text: `${workout.title}: ${workout.rationale}`, data: workout };
}

export async function toolPlan(
  state: McpState,
  args: {
    demo: boolean;
    /** MVP-026: `build` = a no-race fitness block (4-16 weeks, clamped). */
    kind?: PlanKind;
    race?: string;
    weeks?: number;
    start?: string;
    date?: string;
    note?: string;
  },
): Promise<ToolResult> {
  const { profile, activities, goal: storedGoal, dailyLoads } = await loadCtx(state, args.demo);
  const kind: PlanKind = args.kind ?? 'race';
  const build = kind === 'build';
  const distance = args.race ?? storedGoal?.distance ?? '10k';
  const goal = RaceGoal.parse({
    distance,
    name: storedGoal?.name ?? distance,
    // A build plan has no race, so no race date.
    date: build ? undefined : (args.date ?? storedGoal?.date),
  });
  const weeks = args.weeks ?? 8;
  const asOfDate = refNowIso(state, args.demo);
  const startDate = args.start ?? asOfDate.slice(0, 10);
  const context = buildCoachContext({ activities, profile, goal, asOfDate, dailyLoads });
  const { plan, validation, disclaimer, flags, seed } = await generatePlan({
    profile,
    goal,
    weeks,
    kind,
    startDate,
    context,
    note: args.note,
    deps: deps(state),
  });
  if (!validation.valid) {
    // Never hand back a plan that still breaks a guardrail after repair.
    return {
      text: `Plan rejected: ${validation.violations.length} guardrail issue(s) remained after repair.`,
      data: { validation, disclaimer, flags },
      isError: true,
    };
  }
  return {
    text: build
      ? `${plan.weeks.length}-week fitness build (valid${seed ? `; ${seedSummary(seed)}` : ''}).`
      : `${plan.weeks.length}-week ${goal.name ?? goal.distance} plan (valid).`,
    data: { plan, validation, disclaimer, flags, seed },
  };
}
