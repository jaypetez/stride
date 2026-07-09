import { DISCLAIMER, formatPace } from '@stride/core';
import type {
  Activity,
  ActivityMetrics,
  PlanValidation,
  TrainingPlan,
  WorkoutSuggestion,
} from '@stride/schemas';
import pc from 'picocolors';

export function heading(text: string): void {
  console.log(`\n${pc.bold(pc.cyan(text))}`);
}

export function info(text: string): void {
  console.log(text);
}

export function dim(text: string): void {
  console.log(pc.dim(text));
}

export function success(text: string): void {
  console.log(pc.green(`✓ ${text}`));
}

export function warn(text: string): void {
  console.log(pc.yellow(`! ${text}`));
}

export function errorMsg(text: string): void {
  console.error(pc.red(`✗ ${text}`));
}

export function formatDuration(sec: number): string {
  const m = Math.round(sec / 60);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const rem = m % 60;
  return `${h}h${String(rem).padStart(2, '0')}`;
}

function row(label: string, value: string): void {
  console.log(`  ${pc.dim(label.padEnd(20))} ${value}`);
}

export function printMetrics(activity: Activity, m: ActivityMetrics): void {
  heading(`${activity.name}`);
  dim(`  ${activity.startDateLocal ?? activity.startDate} · ${activity.sportType}`);
  row('Distance', `${(m.distanceM / 1000).toFixed(2)} km`);
  row('Moving time', formatDuration(m.durationSec));
  row(
    'Training load',
    `${m.tss} TSS (${m.method})${m.intensityFactor ? ` · IF ${m.intensityFactor}` : ''}`,
  );
  if (m.averagePaceSecPerKm) row('Avg pace', formatPace(m.averagePaceSecPerKm));
  if (m.gradeAdjustedPaceSecPerKm) row('Grade-adj pace', formatPace(m.gradeAdjustedPaceSecPerKm));
  if (m.averageHr) row('Avg HR', `${m.averageHr} bpm`);
  if (m.efficiencyFactor) row('Efficiency factor', String(m.efficiencyFactor));
  if (m.aerobicDecouplingPct !== undefined) row('Aerobic decoupling', `${m.aerobicDecouplingPct}%`);
  if (m.zoneDistribution) {
    const d = m.zoneDistribution;
    row('Intensity split', `${d.easyPct}% easy / ${d.moderatePct}% mod / ${d.hardPct}% hard`);
  }
}

export function printWorkout(w: WorkoutSuggestion): void {
  heading(`Next: ${w.title}`);
  if (w.targetDurationSec) row('Duration', formatDuration(w.targetDurationSec));
  if (w.targetPaceSecPerKm) row('Target pace', formatPace(w.targetPaceSecPerKm));
  if (w.targetHrZone) row('Target HR zone', `Z${w.targetHrZone}`);
  if (w.targetTss) row('Estimated load', `${w.targetTss} TSS`);
  console.log(`\n  ${w.description}`);
  console.log(pc.dim(`\n  Why: ${w.rationale}`));
}

export function printPlan(plan: TrainingPlan, validation: PlanValidation): void {
  heading(`${plan.weeks.length}-week plan → ${plan.goal.name ?? plan.goal.distance}`);
  if (plan.summary) console.log(`  ${plan.summary}\n`);
  for (const week of plan.weeks) {
    const tag = pc.bold(`Week ${week.weekNumber}`);
    console.log(
      `  ${tag} ${pc.magenta(`[${week.phase}]`)} ~${week.targetTss} TSS · ~${week.targetDistanceKm} km`,
    );
    const sessions = week.days
      .flatMap((d) => d.sessions)
      .filter((s) => s.type !== 'rest')
      .map((s) => s.title.replace(/\s*\(.*\)$/, ''));
    console.log(pc.dim(`     ${sessions.join(' · ')}`));
  }
  if (validation.violations.length > 0) {
    console.log('');
    for (const v of validation.violations) {
      const line = `  guardrail[${v.rule}]: ${v.message}`;
      if (v.severity === 'error') errorMsg(line);
      else warn(line);
    }
  } else {
    console.log(
      pc.green('\n  ✓ Plan passes all guardrails (ramp, rest, back-to-back, long-run caps).'),
    );
  }
}

export function printFlags(flags: string[]): void {
  if (flags.length === 0) return;
  console.log('');
  for (const f of flags) warn(f);
}

export function printAttribution(activity: Activity): void {
  if (activity.source === 'strava') {
    console.log(pc.dim(`\n  View on Strava: https://www.strava.com/activities/${activity.id}`));
    console.log(pc.dim('  Powered by Strava'));
  }
}

export function printDisclaimer(): void {
  console.log(pc.dim(`\n  ${DISCLAIMER}`));
}
