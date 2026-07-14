import type {
  Activity,
  AnalysisResult,
  AthleteProfile,
  CoachContext,
  NextWorkoutResult,
  PlanDay,
  PlanResult,
  PlanValidation,
  RaceGoal,
  RedFlag,
  TrainingPlan,
} from '@stride/schemas';
import { LlmPlanProposal } from '@stride/schemas';
import { DEFAULT_MODELS, type ModelConfig } from '../config';
import { createLogger } from '../log';
import { addDays, computeActivityMetrics, formatPace, toDateKey } from '../science/index';
import type { CoachLLM } from './anthropic';
import { type PlanGuardrailContext, repairPlan, validatePlan } from './guardrail';
import { buildPlanSkeleton, makeSession, materializeProposal, proposeNextWorkout } from './planner';
import {
  buildAnalyzePrompt,
  buildClassifyPrompt,
  buildNextPrompt,
  buildPlanProposalPrompt,
  buildPlanSummaryPrompt,
  SYSTEM_PROMPT,
} from './prompts';
import { classifierWarnings, DISCLAIMER, detectRedFlags, shouldHalt } from './safety';

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

/**
 * Optional Haiku second pass over free text. It ONLY appends WARNING-level flags
 * (mapped from classifier labels) — the deterministic keyword rules stay
 * authoritative for STOP, so safety never depends on model availability. No-op
 * offline (no `classify`) and never runs once a STOP flag is present.
 */
async function augmentWithClassifier(
  flags: RedFlag[],
  note: string | undefined,
  llm: CoachLLM | null | undefined,
  model: string,
): Promise<void> {
  if (!note || !llm?.classify || shouldHalt(flags)) return;
  try {
    const res = await llm.classify({
      model,
      system: SYSTEM_PROMPT,
      prompt: buildClassifyPrompt(note),
    });
    if (!res.refused) flags.push(...classifierWarnings(res.labels));
  } catch (err) {
    log.debug('LLM classify augmentation failed; keeping keyword flags only', {
      err: String(err),
    });
  }
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
  const { streams: _streams, ...summary } = activity;

  // Safety halt: on a STOP flag, return a safe result and DO NOT call the model.
  if (shouldHalt(redFlags)) {
    const stop = redFlags.find((f) => f.severity === 'stop');
    return {
      activity: summary,
      headline: base.headline,
      explanation:
        stop?.message ?? 'Stop exercising and consult a medical professional before continuing.',
      flags: redFlags.map((f) => f.message),
      disclaimer: DISCLAIMER,
    };
  }

  await augmentWithClassifier(redFlags, note, deps?.llm, models(deps).classify);

  let explanation = base.explanation;
  const llm = deps?.llm;
  if (llm) {
    try {
      const res = await llm.complete({
        model: models(deps).chat,
        system: SYSTEM_PROMPT,
        prompt: buildAnalyzePrompt(activity, metrics, context),
        path: 'analyze',
      });
      // Discard refused/truncated output → keep the deterministic explanation.
      if (res.text && !res.refused && res.stopReason !== 'max_tokens') explanation = res.text;
    } catch (err) {
      log.debug('LLM analysis enrichment failed; using deterministic explanation', {
        err: String(err),
      });
    }
  }

  return {
    activity: summary,
    headline: base.headline,
    explanation,
    flags: redFlags.map((f) => f.message),
    disclaimer: DISCLAIMER,
  };
}

/** Suggest the next workout: deterministic prescription + coaching rationale. */
export async function suggestNextWorkout(params: {
  context: CoachContext;
  profile: AthleteProfile;
  note?: string;
  deps?: CoachDeps;
}): Promise<NextWorkoutResult> {
  const { context, profile, note, deps } = params;
  const llm = deps?.llm;
  const redFlags = detectRedFlags({
    text: note,
    profile,
    tsb: context.fitness?.tsb,
    acwrFlag: context.acwr?.flag,
  });
  if (shouldHalt(redFlags)) {
    const suggestion = makeSession('rest', 0, 3.0, {
      rationale:
        redFlags.find((f) => f.severity === 'stop')?.message ?? 'Rest and seek medical advice.',
    });
    return { ...suggestion, disclaimer: DISCLAIMER, flags: redFlags.map((f) => f.message) };
  }

  await augmentWithClassifier(redFlags, note, llm, models(deps).classify);

  const suggestion = proposeNextWorkout(context, profile);
  if (llm) {
    try {
      const res = await llm.complete({
        model: models(deps).chat,
        system: SYSTEM_PROMPT,
        prompt: buildNextPrompt(context, suggestion.title),
        maxTokens: 400,
        path: 'next',
      });
      if (res.text && !res.refused && res.stopReason !== 'max_tokens')
        suggestion.rationale = res.text;
    } catch (err) {
      log.debug('LLM next-workout rationale failed; using deterministic rationale', {
        err: String(err),
      });
    }
  }
  return { ...suggestion, disclaimer: DISCLAIMER, flags: redFlags.map((f) => f.message) };
}

/** Run validate → repair → re-validate (the exact skeleton guardrail flow). */
function runGuardrail(
  plan: TrainingPlan,
  ctx: PlanGuardrailContext,
): { plan: TrainingPlan; validation: PlanValidation } {
  let validation = validatePlan(plan, ctx);
  let out = plan;
  if (!validation.valid) {
    out = repairPlan(plan, ctx).plan;
    validation = { ...validatePlan(out, ctx), repaired: true };
  }
  return { plan: out, validation };
}

/**
 * Generate a periodized plan.
 *
 * - No LLM (all offline / golden tests): use `buildPlanSkeleton` EXACTLY as
 *   before, so golden snapshots stay byte-identical.
 * - LLM present: ask for a STRUCTURAL proposal (Option A) via structured
 *   outputs, materialize it into real numbers with `makeSession`, then run the
 *   hardened guardrail. Valid → return; repaired-to-valid → return (repaired:
 *   true, violations recorded); unrepairable/refused/empty → reject and fall
 *   back to the always-valid skeleton.
 */
export async function generatePlan(params: {
  profile: AthleteProfile;
  goal: RaceGoal;
  weeks: number;
  startDate: string;
  context?: CoachContext;
  note?: string;
  deps?: CoachDeps;
}): Promise<PlanResult> {
  const { profile, goal, weeks, startDate, context, note, deps } = params;
  const createdAt = nowIso(deps);
  const planId = `plan-${startDate}-${weeks}w`;
  // GOAL §7 ramp cap uses CTL when we know current fitness; else cold-start TSS.
  const guardrailCtx: PlanGuardrailContext = {
    seedCtl: context?.fitness?.ctl,
    seedAtl: context?.fitness?.atl,
    experienceLevel: profile.experienceLevel,
  };

  const flags = detectRedFlags({
    text: note,
    profile,
    tsb: context?.fitness?.tsb,
    acwrFlag: context?.acwr?.flag,
  });

  // Safety halt: a STOP flag (e.g. chest pain) must NOT yield a training plan.
  // Return a safe, all-rest single week whose summary carries the referral
  // message — consistent with `analyzeWorkout`/`suggestNextWorkout`. The model
  // is never consulted on a halt.
  if (shouldHalt(flags)) {
    const start = toDateKey(startDate);
    const stopMessage =
      flags.find((f) => f.severity === 'stop')?.message ??
      'Stop exercising and consult a medical professional before continuing.';
    const days: PlanDay[] = Array.from({ length: 7 }, (_, i) => {
      const date = addDays(start, i);
      return { day: i + 1, date, sessions: [makeSession('rest', 0, 3.0, { date })] };
    });
    const safePlan: TrainingPlan = {
      id: planId,
      createdAt,
      goal,
      startDate: start,
      endDate: addDays(start, 6),
      summary: stopMessage,
      weeks: [
        {
          weekNumber: 1,
          phase: 'recovery',
          focus: 'Rest and seek medical guidance before resuming training.',
          targetTss: 0,
          targetDistanceKm: 0,
          days,
        },
      ],
    };
    return {
      plan: safePlan,
      validation: { valid: true, violations: [], repaired: false },
      disclaimer: DISCLAIMER,
      flags: flags.map((f) => f.message),
    };
  }

  const skeleton = () =>
    runGuardrail(
      buildPlanSkeleton({ profile, goal, weeks, startDate, planId, createdAt }),
      guardrailCtx,
    );

  const llm = deps?.llm;
  let result: { plan: TrainingPlan; validation: PlanValidation } | undefined;

  // Structured proposal→materialize→repair→reject loop runs ONLY when an LLM is
  // present (and no STOP flag). The no-key path never enters here.
  if (!shouldHalt(flags) && llm && context) {
    try {
      const proposal = await llm.parse(
        {
          model: models(deps).plan,
          system: SYSTEM_PROMPT,
          prompt: buildPlanProposalPrompt(context, weeks, goal),
          maxTokens: 4096,
          path: 'plan-proposal',
        },
        LlmPlanProposal,
      );
      if (!proposal.refused && proposal.value && proposal.value.weeks.length > 0) {
        const materialized = materializeProposal(proposal.value, {
          profile,
          goal,
          weeks,
          startDate,
          planId,
          createdAt,
        });
        if (materialized.weeks.length > 0) {
          const gr = runGuardrail(materialized, guardrailCtx);
          if (gr.validation.valid) result = gr; // valid or repaired-to-valid
          // else: unrepairable → reject; fall through to the skeleton below.
        }
      }
    } catch (err) {
      log.debug('LLM structured plan failed; falling back to skeleton', { err: String(err) });
    }
  }

  if (!result) result = skeleton();

  // Prose summary enrichment (skip on halt). Additive; never changes numbers.
  if (!shouldHalt(flags) && llm && context) {
    try {
      const res = await llm.complete({
        model: models(deps).plan,
        system: SYSTEM_PROMPT,
        prompt: buildPlanSummaryPrompt(result.plan, context),
        maxTokens: 500,
        path: 'plan-summary',
      });
      if (res.text && !res.refused && res.stopReason !== 'max_tokens') {
        result = { plan: { ...result.plan, summary: res.text }, validation: result.validation };
      }
    } catch (err) {
      log.debug('LLM plan summary failed; using deterministic summary', { err: String(err) });
    }
  }

  return {
    plan: result.plan,
    validation: result.validation,
    disclaimer: DISCLAIMER,
    flags: flags.map((f) => f.message),
  };
}
