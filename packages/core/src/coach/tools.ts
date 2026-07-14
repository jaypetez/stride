import type { CoachContext, Zones } from '@stride/schemas';
import {
  GetNextWorkoutInputsInput,
  GetPaceZonesInput,
  GetPlanContextInput,
  GetRecentActivitiesInput,
  GetTrainingLoadInput,
} from '@stride/schemas';
import type { z } from 'zod';

/**
 * The single source of read-only "facts" the coach exposes — to the Claude tool
 * runner AND to the MCP server. Both build a provider and call the SAME tool
 * functions, so MCP and the coach return byte-identical values (GOAL §8). Every
 * value here is already computed by the deterministic engine; the tools only
 * project the pre-built `CoachContext` (+ zones), never compute anything.
 */
export interface CoachDataProvider {
  getContext(): Promise<CoachContext> | CoachContext;
  getZones(): Promise<Zones> | Zones;
}

/** A tool's output: a one-line human summary plus the structured facts. */
export interface CoachToolResult {
  summary: string;
  data: unknown;
}

/**
 * A read-only tool definition. `inputSchema` lives in `@stride/schemas` and is
 * reused verbatim by both consumers; `examples` raise complex-parameter
 * accuracy in the tool runner (GOAL §8) and are stable so the tool prefix caches.
 */
export interface CoachToolDef<I = unknown> {
  name: string;
  description: string;
  inputSchema: z.ZodType<I>;
  examples: I[];
  run(provider: CoachDataProvider, input: I): Promise<CoachToolResult>;
}

// --- The five §8 tool functions (pure over a provider) ---

async function getTrainingLoad(provider: CoachDataProvider): Promise<CoachToolResult> {
  const ctx = await provider.getContext();
  const f = ctx.fitness ?? null;
  const a = ctx.acwr ?? null;
  const ramp = ctx.rampRatePerWeek ?? null;
  const summary = f
    ? `Fitness (CTL) ${f.ctl}, Fatigue (ATL) ${f.atl}, Form (TSB) ${f.tsb}. ACWR ${a?.acwr ?? '—'} (${a?.flag ?? 'n/a'}). Ramp ${ramp ?? '—'}/week.`
    : 'No training-load data available (sync activities first, or use demo mode).';
  return { summary, data: { fitness: f, acwr: a, rampRatePerWeek: ramp } };
}

async function getRecentActivities(
  provider: CoachDataProvider,
  input: GetRecentActivitiesInput,
): Promise<CoachToolResult> {
  const ctx = await provider.getContext();
  const limit = input.limit ?? 10;
  const items = ctx.recentActivities.slice(0, limit);
  return { summary: `${items.length} recent activities.`, data: items };
}

async function getPaceZones(provider: CoachDataProvider): Promise<CoachToolResult> {
  const zones = await provider.getZones();
  return {
    summary: `${zones.hr.length} HR zones, ${zones.pace.length} pace zones.`,
    data: zones,
  };
}

async function getNextWorkoutInputs(provider: CoachDataProvider): Promise<CoachToolResult> {
  const ctx = await provider.getContext();
  const d = ctx.weeklyDistribution;
  const data = {
    tsb: ctx.fitness?.tsb ?? null,
    ctl: ctx.fitness?.ctl ?? null,
    acwrFlag: ctx.acwr?.flag ?? null,
    weeklyEasyPct: d?.easyPct ?? null,
    weeklyModeratePct: d?.moderatePct ?? null,
    weeklyHardPct: d?.hardPct ?? null,
    weeklyVolumeKm: ctx.weeklyVolumeKm ?? null,
    planPhase: ctx.planPhase ?? null,
    daysToRace: ctx.daysToRace ?? null,
    lastActivity: ctx.recentActivities[0] ?? null,
  };
  return {
    summary: `Inputs for the next-workout decision (TSB ${data.tsb ?? '—'}, phase ${data.planPhase ?? '—'}).`,
    data,
  };
}

async function getPlanContext(provider: CoachDataProvider): Promise<CoachToolResult> {
  const ctx = await provider.getContext();
  const data = {
    goal: ctx.goal ?? null,
    daysToRace: ctx.daysToRace ?? null,
    planPhase: ctx.planPhase ?? null,
    experienceLevel: ctx.profile.experienceLevel,
    fitness: ctx.fitness ?? null,
    rampRatePerWeek: ctx.rampRatePerWeek ?? null,
    weeklyVolumeKm: ctx.weeklyVolumeKm ?? null,
  };
  return {
    summary: `Plan context for ${ctx.goal?.name ?? ctx.goal?.distance ?? 'no goal'} (${data.daysToRace ?? '—'} days out).`,
    data,
  };
}

/**
 * The frozen, ordered toolset. Order is stable so the tool block at the front of
 * a cached request never shifts (GOAL §8 "keep the tool set small and stable").
 */
export const COACH_TOOLS: readonly CoachToolDef[] = [
  {
    name: 'get_training_load',
    description:
      'Current fitness (CTL), fatigue (ATL), form (TSB), the ACWR injury-risk guardrail, and the CTL ramp rate. Read-only, already computed.',
    inputSchema: GetTrainingLoadInput,
    examples: [{}],
    run: (p) => getTrainingLoad(p),
  } as CoachToolDef<GetTrainingLoadInput>,
  {
    name: 'get_recent_activities',
    description:
      'Recent activity summaries (date, name, distance, duration, TSS, pace, HR). Read-only. Pass an optional limit (default 10).',
    inputSchema: GetRecentActivitiesInput,
    examples: [{}, { limit: 5 }],
    run: (p, i) => getRecentActivities(p, i),
  } as CoachToolDef<GetRecentActivitiesInput>,
  {
    name: 'get_pace_zones',
    description:
      "The athlete's heart-rate and pace training zones, derived from their threshold anchors. Read-only.",
    inputSchema: GetPaceZonesInput,
    examples: [{}],
    run: (p) => getPaceZones(p),
  } as CoachToolDef<GetPaceZonesInput>,
  {
    name: 'get_next_workout_inputs',
    description:
      'The pre-computed signals that drive the next-workout decision: form (TSB), ACWR flag, weekly intensity distribution, weekly volume, plan phase, days to race, and the most recent activity. Read-only.',
    inputSchema: GetNextWorkoutInputsInput,
    examples: [{}],
    run: (p) => getNextWorkoutInputs(p),
  } as CoachToolDef<GetNextWorkoutInputsInput>,
  {
    name: 'get_plan_context',
    description:
      'The context for building or reasoning about a training plan: goal race, days to race, current phase, experience level, fitness, ramp rate, and weekly volume. Read-only.',
    inputSchema: GetPlanContextInput,
    examples: [{}],
    run: (p) => getPlanContext(p),
  } as CoachToolDef<GetPlanContextInput>,
];

/** Look up a tool by name (frozen order preserved). */
export function findCoachTool(name: string): CoachToolDef | undefined {
  return COACH_TOOLS.find((t) => t.name === name);
}

/** Run one tool by name against a provider (validates input via its schema). */
export async function runCoachTool(
  provider: CoachDataProvider,
  name: string,
  input: unknown = {},
): Promise<CoachToolResult> {
  const tool = findCoachTool(name);
  if (!tool) throw new Error(`Unknown coach tool: ${name}`);
  const parsed = tool.inputSchema.parse(input ?? {});
  return tool.run(provider, parsed);
}
