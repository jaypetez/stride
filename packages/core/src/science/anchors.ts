import type { Activity, AthleteProfile } from '@stride/schemas';
import { thresholdSpeedFromVdot, vdotFromEffort } from './vdot';

/**
 * Physiological anchors resolve threshold pace/speed and HR references from the
 * athlete profile, falling back to estimates when values are missing. Everything
 * downstream (load, zones, PMC) depends on these — recompute every 4-6 weeks.
 */

export function resolveMaxHr(profile: AthleteProfile): number | undefined {
  if (profile.maxHr) return profile.maxHr;
  if (profile.age) return 208 - 0.7 * profile.age; // Tanaka
  return undefined;
}

export function resolveLthr(profile: AthleteProfile): number | undefined {
  if (profile.lthr) return profile.lthr;
  const max = resolveMaxHr(profile);
  return max ? 0.9 * max : undefined;
}

export function resolveRestingHr(profile: AthleteProfile): number {
  return profile.restingHr ?? 60;
}

export function resolveThresholdSpeed(profile: AthleteProfile): number | undefined {
  if (profile.thresholdSpeedMps) return profile.thresholdSpeedMps;
  if (profile.vdot) return thresholdSpeedFromVdot(profile.vdot);
  return undefined;
}

export interface EstimatedAnchors {
  vdot?: number;
  thresholdSpeedMps?: number;
  maxHr?: number;
  lthr?: number;
}

const RUN_TYPES = new Set(['run', 'trail_run', 'treadmill_run']);

/**
 * Estimate anchors from activity history. Threshold comes from the best (highest
 * VDOT) sustained effort; HR references come from observed maxima. This is a
 * pragmatic MVP heuristic — treat results as trends, not truth.
 */
export function estimateAnchors(activities: Activity[]): EstimatedAnchors {
  let bestVdot = 0;
  let maxHrObserved = 0;

  for (const a of activities) {
    if (a.maxHeartrate && a.maxHeartrate > maxHrObserved) {
      maxHrObserved = a.maxHeartrate;
    }
    if (!RUN_TYPES.has(a.sportType)) continue;
    // Skip manual (self-reported) and treadmill (belt-estimated distance)
    // efforts: their pace can't be trusted to fit a VDOT anchor.
    if (a.manual || a.trainer) continue;
    // Only efforts long enough to be meaningful (>= 10 min) and with distance.
    if (a.movingTime < 600 || a.distance <= 0) continue;
    const vdot = vdotFromEffort(a.distance, a.movingTime);
    if (vdot > bestVdot) bestVdot = vdot;
  }

  const result: EstimatedAnchors = {};
  if (bestVdot > 0) {
    result.vdot = Number(bestVdot.toFixed(1));
    result.thresholdSpeedMps = Number(thresholdSpeedFromVdot(bestVdot).toFixed(3));
  }
  if (maxHrObserved > 0) {
    result.maxHr = maxHrObserved;
    result.lthr = Number((0.9 * maxHrObserved).toFixed(0));
  }
  return result;
}
