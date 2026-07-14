import type {
  DailyLoad,
  ExperienceLevel,
  GuardrailViolation,
  PlanDay,
  PlanValidation,
  PlanWeek,
  TrainingPlan,
} from '@stride/schemas';
import { addDays, buildPmcSeries, ctlRampCap } from '../science/index';
import { makeSession, QUALITY_TYPES, rescaleSession, thresholdFromSession } from './planner';

/** Max week-over-week TSS growth between loading weeks (cold-start fallback). */
export const MAX_WEEKLY_RAMP = 1.35;
export const MIN_REST_DAYS = 1;
export const LONG_RUN_MAX_FRACTION = 0.45;
/** Never scale a session below this in repair (keeps sessions coaching-useful). */
const MIN_SESSION_MIN = 10;
/** Bound the repair scaling loops so they always terminate. */
const MAX_REPAIR_ITERS = 40;
/** Arbitrary epoch for projecting the plan's daily TSS onto a calendar. */
const PROJECTION_EPOCH = '2000-01-01';

/**
 * Optional athlete context that upgrades the ramp check from a coarse
 * week-over-week TSS ratio to GOAL §7's CTL-based cap (5–7 pts/week). When
 * `seedCtl` is present we project the plan's per-session `targetTss` through the
 * PMC EWMA (seeded with the athlete's current fitness) and measure how much CTL
 * each week actually adds; without it we fall back to the TSS ratio (cold start).
 */
export interface PlanGuardrailContext {
  seedCtl?: number;
  seedAtl?: number;
  experienceLevel?: ExperienceLevel;
}

function isHardDay(day: PlanDay): boolean {
  return day.sessions.some((s) => QUALITY_TYPES.has(s.type));
}

function isRestDay(day: PlanDay): boolean {
  return day.sessions.every((s) => s.type === 'rest' || s.type === 'recovery');
}

function dayTss(day: PlanDay): number {
  return day.sessions.reduce((a, x) => a + (x.targetTss ?? 0), 0);
}

function sessionsTss(week: PlanWeek): number {
  return week.days.reduce((s, d) => s + dayTss(d), 0);
}

function weekTss(week: PlanWeek): number {
  return week.targetTss ?? sessionsTss(week);
}

function weekDurationSec(week: PlanWeek): number {
  return week.days.reduce(
    (s, d) => s + d.sessions.reduce((a, x) => a + (x.targetDurationSec ?? 0), 0),
    0,
  );
}

function isLoadingWeek(week: PlanWeek): boolean {
  return week.phase !== 'recovery' && week.phase !== 'taper';
}

function hasTargetTss(plan: TrainingPlan): boolean {
  return plan.weeks.some((w) => w.days.some((d) => dayTss(d) > 0));
}

interface WeekRise {
  endCtl: number;
  rise: number;
}

/**
 * Project the plan's per-session `targetTss` onto a consecutive daily series and
 * run the PMC EWMA (seeded with the athlete's current CTL/ATL) so we can measure
 * the CTL (fitness) rise each week adds — the GOAL §7 ramp metric. Returns the
 * end-of-week CTL and the rise over that week for every week, keyed by number.
 */
function projectWeekRises(
  plan: TrainingPlan,
  seedCtl: number,
  seedAtl: number,
): Map<number, WeekRise> {
  const dailies: DailyLoad[] = plan.weeks.flatMap((week) =>
    week.days.map((day) => ({
      date: addDays(PROJECTION_EPOCH, (week.weekNumber - 1) * 7 + (day.day - 1)),
      tss: dayTss(day),
      durationSec: 0,
      distanceM: 0,
      method: 'none' as const,
      activityIds: [] as string[],
    })),
  );
  const rises = new Map<number, WeekRise>();
  if (dailies.length === 0) return rises;
  dailies.sort((a, b) => a.date.localeCompare(b.date));

  const maxWeek = Math.max(...plan.weeks.map((w) => w.weekNumber));
  const through = addDays(PROJECTION_EPOCH, maxWeek * 7 - 1);
  const pmc = buildPmcSeries(dailies, { seedCtl, seedAtl, throughDate: through });
  const ctlByDate = new Map(pmc.map((p) => [p.date, p.ctl]));

  let prevEnd = seedCtl;
  for (const week of [...plan.weeks].sort((a, b) => a.weekNumber - b.weekNumber)) {
    const endDate = addDays(PROJECTION_EPOCH, (week.weekNumber - 1) * 7 + 6);
    const endCtl = ctlByDate.get(endDate) ?? prevEnd;
    rises.set(week.weekNumber, { endCtl, rise: endCtl - prevEnd });
    prevEnd = endCtl;
  }
  return rises;
}

/**
 * Deterministic plan guardrail. JSON schemas can't express numeric bounds, so
 * every safety rule (ramp cap, no back-to-back hard days, weekly rest, long-run
 * cap) is enforced here. The plan is a proposal; this validator is the enforcer.
 *
 * With an athlete `ctx` (seed CTL/ATL + experience) the ramp rule uses the
 * GOAL-aligned CTL cap; without it, the week-over-week TSS ratio (cold start).
 */
export function validatePlan(plan: TrainingPlan, ctx: PlanGuardrailContext = {}): PlanValidation {
  const violations: GuardrailViolation[] = [];
  const weeks = [...plan.weeks].sort((a, b) => a.weekNumber - b.weekNumber);

  const useCtlRamp = ctx.seedCtl !== undefined && hasTargetTss(plan);
  const experience = ctx.experienceLevel ?? 'intermediate';
  const rampCap = ctlRampCap(experience);
  const rises = useCtlRamp ? projectWeekRises(plan, ctx.seedCtl ?? 0, ctx.seedAtl ?? 0) : undefined;

  let prevLoadTss: number | undefined;
  for (const week of weeks) {
    const tss = weekTss(week);

    // Ramp: CTL-based cap when seeded, else week-over-week TSS ratio.
    if (isLoadingWeek(week)) {
      if (rises) {
        const rise = rises.get(week.weekNumber)?.rise ?? 0;
        if (rise > rampCap) {
          violations.push({
            rule: 'ramp',
            severity: 'error',
            weekNumber: week.weekNumber,
            message: `Week ${week.weekNumber} adds ${rise.toFixed(1)} CTL points (cap ${rampCap}/week for ${experience} athletes).`,
          });
        }
      } else if (
        prevLoadTss !== undefined &&
        prevLoadTss > 0 &&
        tss > prevLoadTss * MAX_WEEKLY_RAMP
      ) {
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
        severity: 'error',
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
        severity: 'error',
        weekNumber: week.weekNumber,
        message: `Week ${week.weekNumber}'s longest run is ${Math.round((longestSec / totalSec) * 100)}% of weekly volume (cap ${Math.round(LONG_RUN_MAX_FRACTION * 100)}%).`,
      });
    }
  }

  return { valid: !violations.some((v) => v.severity === 'error'), violations, repaired: false };
}

/** Deep-clone the plan's weeks/days/sessions so repair never mutates the input. */
function cloneWeeks(plan: TrainingPlan): PlanWeek[] {
  return plan.weeks.map((w) => ({
    ...w,
    days: w.days.map((d) => ({ ...d, sessions: d.sessions.map((s) => ({ ...s })) })),
  }));
}

/** Recompute a week's cached `targetTss`/`targetDistanceKm` from its sessions. */
function withWeekTotals(week: PlanWeek): PlanWeek {
  const tss = week.days.reduce((s, d) => s + dayTss(d), 0);
  const distanceM = week.days.reduce(
    (s, d) => s + d.sessions.reduce((a, x) => a + (x.targetDistanceM ?? 0), 0),
    0,
  );
  return {
    ...week,
    targetTss: Number(tss.toFixed(0)),
    targetDistanceKm: Number((distanceM / 1000).toFixed(1)),
  };
}

/** Cap the week's longest session so it is ≤ LONG_RUN_MAX_FRACTION of volume. */
function capLongRun(days: PlanDay[], weekNumber: number, fixed: GuardrailViolation[]): PlanDay[] {
  const totalSec = days.reduce(
    (s, d) => s + d.sessions.reduce((a, x) => a + (x.targetDurationSec ?? 0), 0),
    0,
  );
  if (totalSec <= 0) return days;

  let maxSec = 0;
  let di = -1;
  let si = -1;
  for (let dIdx = 0; dIdx < days.length; dIdx++) {
    const { sessions } = days[dIdx];
    for (let sIdx = 0; sIdx < sessions.length; sIdx++) {
      const dur = sessions[sIdx].targetDurationSec ?? 0;
      if (dur > maxSec) {
        maxSec = dur;
        di = dIdx;
        si = sIdx;
      }
    }
  }
  if (maxSec <= 0 || maxSec / totalSec <= LONG_RUN_MAX_FRACTION) return days;

  // Solve L' / (rest + L') ≤ FRACTION  ⇒  L' ≤ FRACTION/(1-FRACTION) · rest.
  const restSec = totalSec - maxSec;
  const newSec = (LONG_RUN_MAX_FRACTION / (1 - LONG_RUN_MAX_FRACTION)) * restSec;
  const newMin = Math.max(MIN_SESSION_MIN, Math.floor(newSec / 60));
  const clone = days.map((d) => ({ ...d, sessions: [...d.sessions] }));
  clone[di].sessions[si] = rescaleSession(clone[di].sessions[si], newMin);
  fixed.push({
    rule: 'long_run_cap',
    severity: 'error',
    weekNumber,
    message: `Repaired: capped week ${weekNumber}'s long run at ${newMin} min (≤${Math.round(LONG_RUN_MAX_FRACTION * 100)}% of weekly volume).`,
  });
  return clone;
}

/** Scale a week's quality + long sessions down by `factor`; null if none can. */
function scaleWeekQualityLong(week: PlanWeek, factor: number): PlanWeek | null {
  let changed = false;
  const days = week.days.map((d) => ({
    ...d,
    sessions: d.sessions.map((s) => {
      const scalable = s.type === 'long' || QUALITY_TYPES.has(s.type);
      const durSec = s.targetDurationSec ?? 0;
      if (scalable && durSec > 0) {
        const newMin = (durSec * factor) / 60;
        if (newMin >= MIN_SESSION_MIN) {
          changed = true;
          return rescaleSession(s, newMin);
        }
      }
      return s;
    }),
  }));
  return changed ? { ...week, days } : null;
}

/**
 * Repair error-level violations and return the repaired plan plus the fixes.
 * In order: (1) convert the later of two consecutive hard days to easy; (2)
 * insert a rest day when below the weekly minimum; (3) cap an oversized long
 * run; (4) scale a loading week down until its ramp (CTL rise when seeded, else
 * week-over-week TSS) is within cap. Re-validate afterwards to see what remains.
 */
export function repairPlan(
  plan: TrainingPlan,
  ctx: PlanGuardrailContext = {},
): { plan: TrainingPlan; fixed: GuardrailViolation[] } {
  const fixed: GuardrailViolation[] = [];
  let weeks = cloneWeeks(plan);

  // Per-week structural repairs (independent of load projection).
  weeks = weeks.map((week) => {
    let days = [...week.days].sort((a, b) => a.day - b.day);

    // (1) Back-to-back hard: downgrade the later day to easy.
    for (let i = 1; i < days.length; i++) {
      if (days[i].day === days[i - 1].day + 1 && isHardDay(days[i]) && isHardDay(days[i - 1])) {
        const hard = days[i].sessions[0];
        const dur = (hard?.targetDurationSec ?? 2700) / 60;
        // Recover the athlete's real threshold speed from the hard session being
        // downgraded so the easy day is paced/sized off their anchor, not a
        // hardcoded default (which would misprescribe pace and distance).
        const threshold = hard ? thresholdFromSession(hard) : undefined;
        days[i] = {
          ...days[i],
          sessions: [
            makeSession('easy', dur, threshold ?? 3.0, {
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

    // (2) Weekly rest minimum: convert the lowest-load non-quality day to rest.
    const restCount = days.filter(isRestDay).length;
    if (restCount < MIN_REST_DAYS) {
      const candidates = days
        .map((d, idx) => ({ idx, day: d, load: dayTss(d) }))
        .filter((c) => !isRestDay(c.day) && !isHardDay(c.day))
        .sort((a, b) => a.load - b.load);
      const needed = MIN_REST_DAYS - restCount;
      for (let k = 0; k < needed && k < candidates.length; k++) {
        const { idx, day } = candidates[k];
        days[idx] = {
          ...day,
          sessions: [
            makeSession('rest', 0, 3.0, {
              date: day.date,
              rationale: 'Inserted a rest day to meet the weekly recovery minimum.',
            }),
          ],
        };
        fixed.push({
          rule: 'rest_minimum',
          severity: 'error',
          weekNumber: week.weekNumber,
          message: `Repaired: converted day ${day.day} to a rest day.`,
        });
      }
    }

    // (3) Long-run cap.
    days = capLongRun(days, week.weekNumber, fixed);

    return withWeekTotals({ ...week, days });
  });

  // (4) Ramp: scale loading weeks down until within cap.
  const useCtlRamp = ctx.seedCtl !== undefined && hasTargetTss({ ...plan, weeks });
  const experience = ctx.experienceLevel ?? 'intermediate';
  const rampCap = ctlRampCap(experience);
  const loadingOrder = weeks
    .filter(isLoadingWeek)
    .sort((a, b) => a.weekNumber - b.weekNumber)
    .map((w) => w.weekNumber);

  let prevLoadTss: number | undefined;
  for (const weekNumber of loadingOrder) {
    let idx = weeks.findIndex((w) => w.weekNumber === weekNumber);
    let scaled = false;

    if (useCtlRamp) {
      for (let iter = 0; iter < MAX_REPAIR_ITERS; iter++) {
        const rise = projectWeekRises({ ...plan, weeks }, ctx.seedCtl ?? 0, ctx.seedAtl ?? 0).get(
          weekNumber,
        )?.rise;
        if (rise === undefined || rise <= rampCap) break;
        const next = scaleWeekQualityLong(weeks[idx], 0.85);
        if (!next) break;
        weeks[idx] = withWeekTotals(next);
        scaled = true;
      }
    } else if (
      prevLoadTss !== undefined &&
      prevLoadTss > 0 &&
      weekTss(weeks[idx]) > prevLoadTss * MAX_WEEKLY_RAMP
    ) {
      for (let iter = 0; iter < MAX_REPAIR_ITERS; iter++) {
        if (weekTss(weeks[idx]) <= prevLoadTss * MAX_WEEKLY_RAMP) break;
        const next = scaleWeekQualityLong(weeks[idx], 0.9);
        if (!next) break;
        weeks[idx] = withWeekTotals(next);
        scaled = true;
      }
    }

    if (scaled) {
      idx = weeks.findIndex((w) => w.weekNumber === weekNumber);
      const detail = useCtlRamp
        ? `CTL rise ≤ ${rampCap} pts/week`
        : `≤ ${Math.round((MAX_WEEKLY_RAMP - 1) * 100)}% week-over-week`;
      fixed.push({
        rule: 'ramp',
        severity: 'error',
        weekNumber,
        message: `Repaired: scaled week ${weekNumber} down to keep the ramp within cap (${detail}).`,
      });
    }
    prevLoadTss = weekTss(weeks[idx]);
  }

  return { plan: { ...plan, weeks }, fixed };
}
