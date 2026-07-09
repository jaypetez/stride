import type { Activity, ActivityMetrics, CoachContext, TrainingPlan } from '@stride/schemas';
import { formatPace } from '../science/index';
import { DISCLAIMER } from './safety';

/**
 * The frozen coaching persona. Kept stable and placed at the front of every
 * request so it caches well. It reasons over pre-computed numbers and never
 * computes them.
 */
export const SYSTEM_PROMPT = `You are Stride, an evidence-based, empathetic AI running coach.

Core rules:
- You reason over PRE-COMPUTED metrics that are given to you. NEVER compute, estimate, or invent numbers (pace, heart rate, TSS, distances). If a number is not provided, do not make one up.
- Ground your advice in accepted endurance principles: progressive overload, ~80/20 polarized intensity, periodization (base → build → peak → taper), and recovery.
- Always attach a short physiological rationale — the "why" — to any recommendation. This improves adherence.
- Be concise, practical, and encouraging. Prefer a few clear sentences over long essays.
- Stay in scope: general fitness guidance only. You are not a doctor. If red-flag symptoms are mentioned (chest pain, dizziness, fainting, etc.), tell the athlete to stop and consult a medical professional.
- ${DISCLAIMER}`;

export function formatContext(ctx: CoachContext): string {
  const lines: string[] = [];
  const p = ctx.profile;
  lines.push(
    `Athlete: ${p.name ?? 'athlete'} (${p.experienceLevel}${p.age ? `, age ${p.age}` : ''}).`,
  );
  if (p.goals) lines.push(`Stated goals: ${p.goals}`);
  if (ctx.goal) {
    lines.push(
      `Goal race: ${ctx.goal.name ?? ctx.goal.distance}${ctx.goal.date ? ` on ${ctx.goal.date}` : ''}${
        ctx.daysToRace !== undefined ? ` (${ctx.daysToRace} days away)` : ''
      }.`,
    );
  }
  if (ctx.planPhase) lines.push(`Current training phase: ${ctx.planPhase}.`);
  if (ctx.fitness) {
    lines.push(
      `Fitness/Fatigue/Form — CTL ${ctx.fitness.ctl}, ATL ${ctx.fitness.atl}, TSB ${ctx.fitness.tsb}.`,
    );
  }
  if (ctx.rampRatePerWeek !== undefined) lines.push(`CTL ramp rate: ${ctx.rampRatePerWeek}/week.`);
  if (ctx.acwr) lines.push(`ACWR: ${ctx.acwr.acwr} (${ctx.acwr.flag}).`);
  if (ctx.weeklyDistribution) {
    const d = ctx.weeklyDistribution;
    lines.push(
      `Last 7 days intensity distribution — easy ${d.easyPct}%, moderate ${d.moderatePct}%, hard ${d.hardPct}% (target ~80% easy / 20% hard).`,
    );
  }
  if (ctx.weeklyVolumeKm !== undefined) lines.push(`Last 7 days volume: ${ctx.weeklyVolumeKm} km.`);
  if (ctx.recentActivities.length) {
    lines.push('Recent activities (most recent first):');
    for (const a of ctx.recentActivities.slice(0, 6)) {
      const pace = a.avgPaceSecPerKm ? `, ${formatPace(a.avgPaceSecPerKm)}` : '';
      const hr = a.avgHr ? `, avg HR ${a.avgHr}` : '';
      lines.push(
        `  - ${a.date} ${a.name}: ${a.distanceKm} km, ${Math.round(a.durationSec / 60)} min, ${a.tss} TSS (${a.loadMethod})${pace}${hr}`,
      );
    }
  }
  return lines.join('\n');
}

export function formatMetrics(activity: Activity, m: ActivityMetrics): string {
  const lines: string[] = [];
  lines.push(
    `Activity: ${activity.name} (${activity.sportType}) on ${activity.startDateLocal ?? activity.startDate}.`,
  );
  lines.push(
    `Distance ${(m.distanceM / 1000).toFixed(2)} km, moving time ${Math.round(m.durationSec / 60)} min.`,
  );
  lines.push(
    `Training load: ${m.tss} TSS via ${m.method}${m.intensityFactor ? `, IF ${m.intensityFactor}` : ''}.`,
  );
  if (m.averagePaceSecPerKm) lines.push(`Average pace: ${formatPace(m.averagePaceSecPerKm)}.`);
  if (m.gradeAdjustedPaceSecPerKm)
    lines.push(`Grade-adjusted pace (NGP): ${formatPace(m.gradeAdjustedPaceSecPerKm)}.`);
  if (m.averageHr) lines.push(`Average HR: ${m.averageHr} bpm.`);
  if (m.efficiencyFactor) lines.push(`Efficiency Factor: ${m.efficiencyFactor}.`);
  if (m.aerobicDecouplingPct !== undefined)
    lines.push(`Aerobic decoupling: ${m.aerobicDecouplingPct}% (<5% is good durability).`);
  if (m.zoneDistribution) {
    const d = m.zoneDistribution;
    lines.push(
      `Intensity split: easy ${d.easyPct}%, moderate ${d.moderatePct}%, hard ${d.hardPct}%.`,
    );
  }
  return lines.join('\n');
}

export function buildAnalyzePrompt(
  activity: Activity,
  m: ActivityMetrics,
  ctx?: CoachContext,
): string {
  return [
    'Explain this completed run to the athlete in 3-5 sentences. Interpret the numbers (do not restate them all), note what went well or is worth watching, and give one takeaway. Attach the physiological "why".',
    '',
    'PRE-COMPUTED METRICS (do not alter these numbers):',
    formatMetrics(activity, m),
    ctx ? `\nTRAINING CONTEXT:\n${formatContext(ctx)}` : '',
  ].join('\n');
}

export function buildNextPrompt(ctx: CoachContext, proposedTitle: string): string {
  return [
    `The code has selected the athlete's next session: "${proposedTitle}". Write a short (2-3 sentence) coaching rationale for why this session fits right now. Reference the athlete's current form and recent training. Do NOT change the prescription or invent numbers.`,
    '',
    'TRAINING CONTEXT (pre-computed):',
    formatContext(ctx),
  ].join('\n');
}

export function buildPlanSummaryPrompt(plan: TrainingPlan, ctx: CoachContext): string {
  const weekLines = plan.weeks
    .map(
      (w) =>
        `Week ${w.weekNumber} (${w.phase}): ~${w.targetTss} TSS, ~${w.targetDistanceKm} km — ${w.focus}`,
    )
    .join('\n');
  return [
    `Write a short, motivating 3-4 sentence overview of this ${plan.weeks.length}-week plan for the athlete. Explain the arc (base → build → peak → taper) and how it serves their goal. Do not invent numbers beyond what is given.`,
    '',
    'GOAL & CONTEXT:',
    formatContext(ctx),
    '',
    'PLAN OUTLINE (pre-computed):',
    weekLines,
  ].join('\n');
}
