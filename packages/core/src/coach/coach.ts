import type {
  Activity,
  AnalysisResult,
  AthleteProfile,
  CoachContext,
  PlanValidation,
  RaceGoal,
  TrainingPlan,
  WorkoutSuggestion,
} from '@stride/schemas';
import { DEFAULT_MODELS, type ModelConfig } from '../config';
import { createLogger } from '../log';
import { computeActivityMetrics, formatPace } from '../science/index';
import type { CoachLLM } from './anthropic';
import { type PlanGuardrailContext, repairPlan, validatePlan } from './guardrail';
import { buildPlanSkeleton, makeSession, proposeNextWorkout } from './planner';
import {
  buildAnalyzePrompt,
  buildNextPrompt,
  buildPlanSummaryPrompt,
  SYSTEM_PROMPT,
} from './prompts';
import { detectRedFlags, shouldHalt } from './safety';

export interface CoachDeps {
  /** LLM for prose enrichment. When absent, deterministic fallbacks are used. */
  llm?: CoachLLM | null;
  models?: ModelConfig;
  /** Injectable clock returning an ISO timestamp (for deterministic tests). */
  nowIso?: () => string;
}

const log = createLogger('coach');

function models(deps?: CoachDeps): ModelConfig {
  return deps?.models ?? DEFAULT_MODELS;
}

function nowIso(deps?: CoachDeps): string {
  return deps?.nowIso ? deps.nowIso() : new Date().toISOString();
}

// --- Deterministic analysis narrative (used with or without an LLM) ---

function composeAnalysis(
  activity: Activity,
  m: ReturnType<typeof computeActivityMetrics>,
): { headline: string; explanation: string } {
  const km = (m.distanceM / 1000).toFixed(1);
  const headline = `${activity.name} · ${km} km · ${m.tss} TSS${m.intensityFactor ? ` · IF ${m.intensityFactor}` : ''}`;

  const parts: string[] = [];
  parts.push(
    `This ${Math.round(m.durationSec / 60)}-minute run carried a training load of ${m.tss} TSS (via ${m.method}).`,
  );
  if (m.averagePaceSecPerKm) {
    const gap =
      m.gradeAdjustedPaceSecPerKm &&
      Math.abs(m.gradeAdjustedPaceSecPerKm - m.averagePaceSecPerKm) > 3
        ? ` (grade-adjusted ${formatPace(m.gradeAdjustedPaceSecPerKm)})`
        : '';
    parts.push(`You averaged ${formatPace(m.averagePaceSecPerKm)}${gap}.`);
  }
  if (m.averageHr && m.efficiencyFactor) {
    parts.push(`At ${m.averageHr} bpm average, your efficiency factor was ${m.efficiencyFactor}.`);
  }
  if (m.aerobicDecouplingPct !== undefined) {
    const verdict =
      m.aerobicDecouplingPct < 5
        ? 'strong aerobic durability — pace held steady relative to heart rate'
        : m.aerobicDecouplingPct < 10
          ? 'moderate cardiac drift — normal for a longer or warmer effort'
          : 'high decoupling — a sign to build more easy aerobic base before adding intensity';
    parts.push(`Aerobic decoupling was ${m.aerobicDecouplingPct}%, indicating ${verdict}.`);
  }
  if (m.zoneDistribution) {
    parts.push(
      `Intensity split: ${m.zoneDistribution.easyPct}% easy / ${m.zoneDistribution.moderatePct}% moderate / ${m.zoneDistribution.hardPct}% hard.`,
    );
  }
  return { headline, explanation: parts.join(' ') };
}

/** Analyze a completed workout: deterministic metrics + an explanation. */
export async function analyzeWorkout(params: {
  activity: Activity;
  profile: AthleteProfile;
  context?: CoachContext;
  note?: string;
  deps?: CoachDeps;
}): Promise<AnalysisResult> {
  const { activity, profile, context, note, deps } = params;
  const metrics = computeActivityMetrics(activity, profile);
  const redFlags = detectRedFlags({
    text: note,
    profile,
    tsb: context?.fitness?.tsb,
    acwrFlag: context?.acwr?.flag,
  });

  const base = composeAnalysis(activity, metrics);
  let explanation = base.explanation;

  const llm = deps?.llm;
  if (llm) {
    try {
      const text = await llm.complete({
        model: models(deps).chat,
        system: SYSTEM_PROMPT,
        prompt: buildAnalyzePrompt(activity, metrics, context),
      });
      if (text) explanation = text;
    } catch (err) {
      log.debug('LLM analysis enrichment failed; using deterministic explanation', {
        err: String(err),
      });
    }
  }

  const { streams: _streams, ...summary } = activity;
  return {
    activity: summary,
    headline: base.headline,
    explanation,
    flags: redFlags.map((f) => f.message),
  };
}

/** Suggest the next workout: deterministic prescription + coaching rationale. */
export async function suggestNextWorkout(params: {
  context: CoachContext;
  profile: AthleteProfile;
  deps?: CoachDeps;
}): Promise<WorkoutSuggestion> {
  const { context, profile, deps } = params;
  const redFlags = detectRedFlags({
    profile,
    tsb: context.fitness?.tsb,
    acwrFlag: context.acwr?.flag,
  });
  if (shouldHalt(redFlags)) {
    return makeSession('rest', 0, 3.0, {
      rationale:
        redFlags.find((f) => f.severity === 'stop')?.message ?? 'Rest and seek medical advice.',
    });
  }

  const suggestion = proposeNextWorkout(context, profile);
  const llm = deps?.llm;
  if (llm) {
    try {
      const text = await llm.complete({
        model: models(deps).chat,
        system: SYSTEM_PROMPT,
        prompt: buildNextPrompt(context, suggestion.title),
        maxTokens: 400,
      });
      if (text) suggestion.rationale = text;
    } catch (err) {
      log.debug('LLM next-workout rationale failed; using deterministic rationale', {
        err: String(err),
      });
    }
  }
  return suggestion;
}

/** Generate a periodized plan (deterministic skeleton), validate & repair it. */
export async function generatePlan(params: {
  profile: AthleteProfile;
  goal: RaceGoal;
  weeks: number;
  startDate: string;
  context?: CoachContext;
  deps?: CoachDeps;
}): Promise<{ plan: TrainingPlan; validation: PlanValidation }> {
  const { profile, goal, weeks, startDate, context, deps } = params;
  const createdAt = nowIso(deps);
  const planId = `plan-${startDate}-${weeks}w`;

  let plan = buildPlanSkeleton({ profile, goal, weeks, startDate, planId, createdAt });
  // GOAL §7 ramp cap uses CTL when we know current fitness; else cold-start TSS.
  const guardrailCtx: PlanGuardrailContext = {
    seedCtl: context?.fitness?.ctl,
    seedAtl: context?.fitness?.atl,
    experienceLevel: profile.experienceLevel,
  };
  let validation = validatePlan(plan, guardrailCtx);
  if (!validation.valid) {
    plan = repairPlan(plan, guardrailCtx).plan;
    validation = { ...validatePlan(plan, guardrailCtx), repaired: true };
  }

  const llm = deps?.llm;
  if (llm && context) {
    try {
      const summary = await llm.complete({
        model: models(deps).plan,
        system: SYSTEM_PROMPT,
        prompt: buildPlanSummaryPrompt(plan, context),
        maxTokens: 500,
      });
      if (summary) plan = { ...plan, summary };
    } catch (err) {
      log.debug('LLM plan summary failed; using deterministic summary', { err: String(err) });
    }
  }

  return { plan, validation };
}
