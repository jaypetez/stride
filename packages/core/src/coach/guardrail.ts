import type { GuardrailViolation, PlanDay, PlanValidation, TrainingPlan } from '@stride/schemas';
import { makeSession, QUALITY_TYPES } from './planner';

/** Max week-over-week TSS growth between loading weeks before it's flagged. */
export const MAX_WEEKLY_RAMP = 1.35;
export const MIN_REST_DAYS = 1;
export const LONG_RUN_MAX_FRACTION = 0.45;

function isHardDay(day: PlanDay): boolean {
  return day.sessions.some((s) => QUALITY_TYPES.has(s.type));
}

function isRestDay(day: PlanDay): boolean {
  return day.sessions.every((s) => s.type === 'rest' || s.type === 'recovery');
}

function weekTss(week: TrainingPlan['weeks'][number]): number {
  return (
    week.targetTss ??
    week.days.reduce((s, d) => s + d.sessions.reduce((a, x) => a + (x.targetTss ?? 0), 0), 0)
  );
}

function weekDurationSec(week: TrainingPlan['weeks'][number]): number {
  return week.days.reduce(
    (s, d) => s + d.sessions.reduce((a, x) => a + (x.targetDurationSec ?? 0), 0),
    0,
  );
}

/**
 * Deterministic plan guardrail. JSON schemas can't express numeric bounds, so
 * every safety rule (ramp cap, no back-to-back hard days, weekly rest, long-run
 * cap) is enforced here. The plan is a proposal; this validator is the enforcer.
 */
export function validatePlan(plan: TrainingPlan): PlanValidation {
  const violations: GuardrailViolation[] = [];
  const weeks = [...plan.weeks].sort((a, b) => a.weekNumber - b.weekNumber);

  let prevLoadTss: number | undefined;
  for (const week of weeks) {
    const tss = weekTss(week);

    // Ramp: compare consecutive loading (non-recovery, non-taper) weeks.
    if (week.phase !== 'recovery' && week.phase !== 'taper') {
      if (prevLoadTss !== undefined && prevLoadTss > 0 && tss > prevLoadTss * MAX_WEEKLY_RAMP) {
        const pct = Math.round((tss / prevLoadTss - 1) * 100);
        violations.push({
          rule: 'ramp',
          severity: 'error',
          weekNumber: week.weekNumber,
          message: `Week ${week.weekNumber} load jumps ${pct}% over the previous loading week (cap is ${Math.round((MAX_WEEKLY_RAMP - 1) * 100)}%).`,
        });
      }
      prevLoadTss = tss;
    }

    // Back-to-back hard days.
    const days = [...week.days].sort((a, b) => a.day - b.day);
    for (let i = 1; i < days.length; i++) {
      if (days[i].day === days[i - 1].day + 1 && isHardDay(days[i]) && isHardDay(days[i - 1])) {
        violations.push({
          rule: 'back_to_back_hard',
          severity: 'error',
          weekNumber: week.weekNumber,
          message: `Week ${week.weekNumber} has hard sessions on consecutive days (${days[i - 1].day} & ${days[i].day}); allow ~48h between quality days.`,
        });
      }
    }

    // Weekly rest minimum.
    const restDays = week.days.filter(isRestDay).length;
    if (restDays < MIN_REST_DAYS) {
      violations.push({
        rule: 'rest_minimum',
        severity: 'warning',
        weekNumber: week.weekNumber,
        message: `Week ${week.weekNumber} has no rest day; include at least ${MIN_REST_DAYS}.`,
      });
    }

    // Long-run cap.
    const totalSec = weekDurationSec(week);
    const longestSec = Math.max(
      0,
      ...week.days.flatMap((d) => d.sessions.map((s) => s.targetDurationSec ?? 0)),
    );
    if (totalSec > 0 && longestSec / totalSec > LONG_RUN_MAX_FRACTION) {
      violations.push({
        rule: 'long_run_cap',
        severity: 'warning',
        weekNumber: week.weekNumber,
        message: `Week ${week.weekNumber}'s longest run is ${Math.round((longestSec / totalSec) * 100)}% of weekly volume (cap ${Math.round(LONG_RUN_MAX_FRACTION * 100)}%).`,
      });
    }
  }

  return { valid: !violations.some((v) => v.severity === 'error'), violations };
}

/**
 * Repair error-level violations: convert the later of two consecutive hard days
 * to an easy run. Returns the repaired plan and the violations that were fixed.
 */
export function repairPlan(plan: TrainingPlan): {
  plan: TrainingPlan;
  fixed: GuardrailViolation[];
} {
  const fixed: GuardrailViolation[] = [];
  const weeks = plan.weeks.map((week) => {
    const days = [...week.days].sort((a, b) => a.day - b.day);
    for (let i = 1; i < days.length; i++) {
      if (days[i].day === days[i - 1].day + 1 && isHardDay(days[i]) && isHardDay(days[i - 1])) {
        const dur = (days[i].sessions[0]?.targetDurationSec ?? 2700) / 60;
        days[i] = {
          ...days[i],
          sessions: [
            makeSession('easy', dur, 3.0, {
              date: days[i].date,
              rationale: 'Downgraded to easy to keep ~48h between quality sessions.',
            }),
          ],
        };
        fixed.push({
          rule: 'back_to_back_hard',
          severity: 'error',
          weekNumber: week.weekNumber,
          message: `Repaired: converted day ${days[i].day} to an easy run.`,
        });
      }
    }
    return { ...week, days };
  });
  return { plan: { ...plan, weeks }, fixed };
}
