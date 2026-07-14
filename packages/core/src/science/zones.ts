import type {
  Activity,
  AthleteProfile,
  HrZone,
  IntensityLabel,
  PaceZone,
  ZoneDistribution,
  Zones,
} from '@stride/schemas';
import { resolveLthr, resolveMaxHr, resolveThresholdSpeed } from './anchors';
import { deriveGradeStream, deriveSpeedStream } from './load';
import { gradeAdjustFactor, timeDeltas } from './util';

/** HR zone lower bounds as fractions of LTHR (Friel, simplified to 5 zones). */
const HR_ZONE_FRACTIONS: Array<{ zone: number; name: string; lo: number }> = [
  { zone: 1, name: 'Recovery', lo: 0 },
  { zone: 2, name: 'Aerobic', lo: 0.85 },
  { zone: 3, name: 'Tempo', lo: 0.9 },
  { zone: 4, name: 'Threshold', lo: 0.95 },
  { zone: 5, name: 'VO2max', lo: 1.0 },
];

export function computeHrZones(lthr: number, maxHr?: number): HrZone[] {
  const top = maxHr && maxHr > lthr ? maxHr : lthr * 1.15;
  return HR_ZONE_FRACTIONS.map((z, i) => {
    const next = HR_ZONE_FRACTIONS[i + 1];
    return {
      zone: z.zone,
      name: z.name,
      minBpm: Math.round(z.lo * lthr),
      maxBpm: next ? Math.round(next.lo * lthr) : Math.round(top),
    };
  });
}

/** Pace zone lower bounds as fractions of threshold speed (Daniels intensities). */
const PACE_ZONE_FRACTIONS: Array<{
  zone: number;
  label: IntensityLabel;
  name: string;
  lo: number;
}> = [
  { zone: 1, label: 'E', name: 'Easy', lo: 0 },
  { zone: 2, label: 'M', name: 'Marathon', lo: 0.88 },
  { zone: 3, label: 'T', name: 'Threshold', lo: 0.94 },
  { zone: 4, label: 'I', name: 'Interval', lo: 1.03 },
  { zone: 5, label: 'R', name: 'Repetition', lo: 1.12 },
];

export function computePaceZones(thresholdSpeedMps: number): PaceZone[] {
  return PACE_ZONE_FRACTIONS.map((z, i) => {
    const next = PACE_ZONE_FRACTIONS[i + 1];
    return {
      zone: z.zone,
      label: z.label,
      name: z.name,
      minSpeedMps: Number((z.lo * thresholdSpeedMps).toFixed(3)),
      maxSpeedMps: Number(((next ? next.lo : 2.0) * thresholdSpeedMps).toFixed(3)),
    };
  });
}

export function computeZones(profile: AthleteProfile): Zones {
  const zones: Zones = { hr: [], pace: [] };
  const lthr = resolveLthr(profile);
  if (lthr) zones.hr = computeHrZones(lthr, resolveMaxHr(profile));
  const threshold = resolveThresholdSpeed(profile);
  if (threshold) zones.pace = computePaceZones(threshold);
  return zones;
}

/**
 * Seconds spent in each HR zone (keyed by zone number) from a HR stream. When a
 * `moving` stream is supplied, non-moving samples are skipped so stops don't
 * inflate the zone seconds (unchanged when it's absent).
 */
export function timeInHrZones(
  hr: number[],
  time: number[] | undefined,
  zones: HrZone[],
  moving?: boolean[],
): Record<string, number> {
  const times = time && time.length === hr.length ? time : hr.map((_, i) => i);
  const dts = timeDeltas(times);
  const hasMoving = !!moving && moving.length === hr.length;
  const out: Record<string, number> = {};
  for (const z of zones) out[String(z.zone)] = 0;
  for (let i = 0; i < hr.length; i++) {
    if (hasMoving && !moving[i]) continue;
    const bpm = hr[i];
    const zone = zones.find((z) => bpm >= z.minBpm && bpm < z.maxBpm) ?? zones[zones.length - 1];
    if (zone) out[String(zone.zone)] += dts[i];
  }
  return out;
}

/** Three-zone (polarized) bucket for an intensity fraction. */
function bucket(fraction: number): 'easy' | 'moderate' | 'hard' {
  if (fraction < 0.9) return 'easy';
  if (fraction < 1.0) return 'moderate';
  return 'hard';
}

/** Easy/moderate/hard seconds for one activity (stream-based when possible). */
export function activityZoneSeconds(
  activity: Activity,
  profile: AthleteProfile,
): { easy: number; moderate: number; hard: number } {
  const acc = { easy: 0, moderate: 0, hard: 0 };
  const lthr = resolveLthr(profile);
  const threshold = resolveThresholdSpeed(profile);
  const s = activity.streams;

  if (lthr && s?.heartrate && s.heartrate.length > 1) {
    const dts = timeDeltas(
      s.time && s.time.length === s.heartrate.length ? s.time : s.heartrate.map((_, i) => i),
    );
    const moving = s.moving && s.moving.length === s.heartrate.length ? s.moving : undefined;
    for (let i = 0; i < s.heartrate.length; i++) {
      if (moving && !moving[i]) continue;
      acc[bucket(s.heartrate[i] / lthr)] += dts[i];
    }
    return acc;
  }
  if (threshold && s) {
    const speeds = deriveSpeedStream(s);
    if (speeds.length > 1) {
      const times = s.time && s.time.length === speeds.length ? s.time : speeds.map((_, i) => i);
      const grades = deriveGradeStream(s, speeds.length);
      const dts = timeDeltas(times);
      const moving = s.moving && s.moving.length === speeds.length ? s.moving : undefined;
      for (let i = 0; i < speeds.length; i++) {
        if (moving && !moving[i]) continue;
        const gap = speeds[i] * gradeAdjustFactor(grades[i] ?? 0);
        acc[bucket(gap / threshold)] += dts[i];
      }
      return acc;
    }
  }
  // Coarse fallback: whole activity into the bucket implied by averages.
  const t = activity.movingTime;
  if (lthr && activity.averageHeartrate) acc[bucket(activity.averageHeartrate / lthr)] += t;
  else if (threshold && activity.averageSpeed) acc[bucket(activity.averageSpeed / threshold)] += t;
  else acc.easy += t; // assume easy if we know nothing
  return acc;
}

/** Aggregate a polarized distribution over a set of activities. */
export function zoneDistribution(
  activities: Activity[],
  profile: AthleteProfile,
): ZoneDistribution {
  let easy = 0;
  let moderate = 0;
  let hard = 0;
  for (const a of activities) {
    const z = activityZoneSeconds(a, profile);
    easy += z.easy;
    moderate += z.moderate;
    hard += z.hard;
  }
  const total = easy + moderate + hard;
  const pct = (x: number) => (total > 0 ? Number(((100 * x) / total).toFixed(1)) : 0);
  return {
    easySec: Math.round(easy),
    moderateSec: Math.round(moderate),
    hardSec: Math.round(hard),
    easyPct: pct(easy),
    moderatePct: pct(moderate),
    hardPct: pct(hard),
  };
}
