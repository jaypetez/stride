import type { Activity, FitnessSeed } from '@stride/schemas';
import { addDays, toDateKey } from './dates';
import { vdotFromEffort } from './vdot';

/**
 * Fitness seed (MVP-025): what the athlete has actually been running lately, so
 * a new plan starts from their real volume, long run and current paces instead
 * of fixed minutes and a best-ever VDOT. Mirrored by the Kotlin port
 * (`org.stride.core.science.plan.fitnessSeed`); the golden `seed.json` pins it.
 */

/** Days in the volume window (the 4 weeks ending on the reference day). */
export const SEED_WINDOW_DAYS = 28;
/** Days in the VDOT window (the 8 weeks ending on the reference day). */
export const SEED_VDOT_WINDOW_DAYS = 56;
/** Fewer runs than this in the volume window means no seed. */
export const SEED_MIN_RUNS = 3;
/** A run shorter than this makes a poor VDOT estimate. */
export const MIN_VDOT_DISTANCE_M = 3000;
/** A run shorter than this (seconds) makes a poor VDOT estimate. */
export const MIN_VDOT_DURATION_SEC = 600;

const RUN_TYPES = new Set(['run', 'trail_run', 'treadmill_run']);

/** True for a run whose pace is trustworthy enough for a VDOT estimate. */
function qualifiesForVdot(a: Activity): boolean {
  return (
    !a.manual &&
    !a.trainer &&
    a.movingTime >= MIN_VDOT_DURATION_SEC &&
    a.distance >= MIN_VDOT_DISTANCE_M
  );
}

/**
 * Summarize recent running for plan seeding.
 *
 * - Runs only (`run`, `trail_run`, `treadmill_run`), bucketed by local day
 *   (`startDateLocal ?? startDate`, first 10 chars).
 * - Volume window: the 28 days ending on `asOf` (inclusive).
 *   `weeklyMinutes` = total moving time / 4, `longestRunMin` = the longest
 *   single run's moving time; both in minutes, rounded to 1 dp.
 * - `vdot` = best VDOT in the 56 days ending on `asOf` from runs that are not
 *   manual or treadmill, at least 10 min and at least 3 km (1 dp), else null.
 * - Returns null when the volume window holds fewer than 3 runs.
 */
export function fitnessSeed(activities: Activity[], asOf: string): FitnessSeed | null {
  const end = toDateKey(asOf);
  const volumeStart = addDays(end, -(SEED_WINDOW_DAYS - 1));
  const vdotStart = addDays(end, -(SEED_VDOT_WINDOW_DAYS - 1));

  let totalSec = 0;
  let longestSec = 0;
  let runCount = 0;
  let bestVdot = 0;
  for (const a of activities) {
    if (!RUN_TYPES.has(a.sportType)) continue;
    const day = toDateKey(a.startDateLocal ?? a.startDate);
    if (day > end || day < vdotStart) continue;
    if (day >= volumeStart) {
      runCount++;
      totalSec += a.movingTime;
      if (a.movingTime > longestSec) longestSec = a.movingTime;
    }
    if (qualifiesForVdot(a)) {
      const vdot = vdotFromEffort(a.distance, a.movingTime);
      if (vdot > bestVdot) bestVdot = vdot;
    }
  }

  if (runCount < SEED_MIN_RUNS) return null;
  return {
    weeklyMinutes: Number((totalSec / 60 / 4).toFixed(1)),
    longestRunMin: Number((longestSec / 60).toFixed(1)),
    vdot: bestVdot > 0 ? Number(bestVdot.toFixed(1)) : null,
    runCount,
  };
}
