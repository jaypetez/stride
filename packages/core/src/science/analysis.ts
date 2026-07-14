import type { Activity, ActivityMetrics, AthleteProfile } from '@stride/schemas';
import { aerobicDecoupling, efficiencyFactor } from './efficiency';
import { computeActivityLoad, deriveGradeStream, deriveSpeedStream } from './load';
import { gradeAdjustFactor, mpsToSecPerKm, timeDeltas } from './util';
import { computeZones, timeInHrZones, zoneDistribution } from './zones';

function round(n: number | undefined, dp = 1): number | undefined {
  if (n === undefined || !Number.isFinite(n)) return undefined;
  return Number(n.toFixed(dp));
}

/**
 * Compute the full set of deterministic metrics for one activity. This is the
 * "facts" object the coach explains — the LLM never recomputes any of it.
 */
export function computeActivityMetrics(
  activity: Activity,
  profile: AthleteProfile,
): ActivityMetrics {
  const load = computeActivityLoad(activity, profile);
  const zones = computeZones(profile);
  const s = activity.streams;

  const averageSpeedMps = load.averageSpeedMps ?? activity.averageSpeed;
  const gradeAdjustedSpeedMps = load.ngpSpeedMps ?? load.meanGapSpeedMps;

  const metrics: ActivityMetrics = {
    activityId: activity.id,
    tss: round(load.tss, 1) ?? 0,
    method: load.method,
    intensityFactor: round(load.intensityFactor, 3),
    durationSec: load.durationSec,
    distanceM: load.distanceM,
    averageSpeedMps: round(averageSpeedMps, 3),
    gradeAdjustedSpeedMps: round(gradeAdjustedSpeedMps, 3),
    averageHr: round(load.averageHr ?? activity.averageHeartrate, 0),
    efficiencyFactor: efficiencyFactor(activity),
    aerobicDecouplingPct: aerobicDecoupling(activity),
    zoneDistribution: zoneDistribution([activity], profile),
  };

  if (averageSpeedMps && averageSpeedMps > 0) {
    metrics.averagePaceSecPerKm = round(mpsToSecPerKm(averageSpeedMps), 1);
  }
  if (gradeAdjustedSpeedMps && gradeAdjustedSpeedMps > 0) {
    metrics.gradeAdjustedPaceSecPerKm = round(mpsToSecPerKm(gradeAdjustedSpeedMps), 1);
  }

  if (s?.heartrate && s.heartrate.length > 1 && zones.hr.length > 0) {
    metrics.hrZoneSeconds = roundRecord(timeInHrZones(s.heartrate, s.time, zones.hr, s.moving));
  }

  if (s && zones.pace.length > 0) {
    const speeds = deriveSpeedStream(s);
    if (speeds.length > 1) {
      const times = s.time && s.time.length === speeds.length ? s.time : speeds.map((_, i) => i);
      const grades = deriveGradeStream(s, speeds.length);
      const dts = timeDeltas(times);
      const moving = s.moving && s.moving.length === speeds.length ? s.moving : undefined;
      const acc: Record<string, number> = {};
      for (const z of zones.pace) acc[String(z.zone)] = 0;
      for (let i = 0; i < speeds.length; i++) {
        if (moving && !moving[i]) continue;
        const gap = speeds[i] * gradeAdjustFactor(grades[i] ?? 0);
        const zone =
          zones.pace.find((z) => gap >= z.minSpeedMps && gap < z.maxSpeedMps) ??
          zones.pace[zones.pace.length - 1];
        if (zone) acc[String(zone.zone)] += dts[i];
      }
      metrics.paceZoneSeconds = roundRecord(acc);
    }
  }

  return metrics;
}

function roundRecord(rec: Record<string, number>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(rec)) out[k] = Math.round(v);
  return out;
}
