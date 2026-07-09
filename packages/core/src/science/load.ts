import type { Activity, ActivityStreams, AthleteProfile, LoadMethod } from '@stride/schemas';
import { resolveLthr, resolveMaxHr, resolveRestingHr, resolveThresholdSpeed } from './anchors';
import {
  clamp,
  fourthPowerNorm,
  gradeAdjustFactor,
  interpolate,
  mean,
  rollingMeanByTime,
  SECONDS_PER_HOUR,
  timeDeltas,
} from './util';

/** Result of computing a single activity's training load. */
export interface LoadResult {
  tss: number;
  method: LoadMethod;
  intensityFactor?: number;
  ngpSpeedMps?: number;
  meanGapSpeedMps?: number;
  averageSpeedMps?: number;
  averageHr?: number;
  durationSec: number;
  distanceM: number;
}

/** %LTHR -> intensity factor mapping (HR is nonlinear vs power/pace). */
const HR_IF_TABLE: ReadonlyArray<readonly [number, number]> = [
  [0.5, 0.5],
  [0.6, 0.55],
  [0.7, 0.65],
  [0.8, 0.75],
  [0.85, 0.8],
  [0.9, 0.85],
  [0.95, 0.92],
  [1.0, 1.0],
  [1.05, 1.06],
  [1.1, 1.12],
  [1.2, 1.2],
];

function ifFromHrFraction(frac: number): number {
  return interpolate(frac, HR_IF_TABLE);
}

/** Instantaneous speed stream (m/s), from velocity or distance/time deltas. */
export function deriveSpeedStream(streams: ActivityStreams): number[] {
  if (streams.velocitySmooth && streams.velocitySmooth.length > 1) {
    return streams.velocitySmooth;
  }
  const { distance, time } = streams;
  if (distance && time && distance.length === time.length && distance.length > 1) {
    const out = new Array<number>(distance.length);
    out[0] = 0;
    for (let i = 1; i < distance.length; i++) {
      const dd = distance[i] - distance[i - 1];
      const dt = time[i] - time[i - 1];
      out[i] = dt > 0 ? Math.max(0, dd / dt) : 0;
    }
    return out;
  }
  return [];
}

/** Grade stream (fraction), from gradeSmooth or altitude/distance deltas. */
export function deriveGradeStream(streams: ActivityStreams, length: number): number[] {
  if (streams.gradeSmooth && streams.gradeSmooth.length === length) {
    return streams.gradeSmooth.map((g) => g / 100);
  }
  const { altitude, distance } = streams;
  if (altitude && distance && altitude.length === length && distance.length === length) {
    const out = new Array<number>(length).fill(0);
    for (let i = 1; i < length; i++) {
      const dd = distance[i] - distance[i - 1];
      const da = altitude[i] - altitude[i - 1];
      out[i] = dd > 0 ? clamp(da / dd, -0.45, 0.45) : 0;
    }
    return out;
  }
  return new Array<number>(length).fill(0);
}

/** Normalized Graded Pace as a speed (m/s), plus the duration-weighted mean GAP. */
export function computeNgp(
  streams: ActivityStreams,
): { ngpSpeedMps: number; meanGapSpeedMps: number } | null {
  const speeds = deriveSpeedStream(streams);
  if (speeds.length < 2) return null;
  const times =
    streams.time && streams.time.length === speeds.length ? streams.time : speeds.map((_, i) => i);
  const grades = deriveGradeStream(streams, speeds.length);
  const gap = speeds.map((s, i) => s * gradeAdjustFactor(grades[i] ?? 0));
  const rolled = rollingMeanByTime(gap, times, 30);
  const dts = timeDeltas(times);
  const ngp = fourthPowerNorm(rolled, dts);
  let weighted = 0;
  let total = 0;
  for (let i = 0; i < gap.length; i++) {
    weighted += gap[i] * dts[i];
    total += dts[i];
  }
  const meanGap = total > 0 ? weighted / total : 0;
  return { ngpSpeedMps: ngp, meanGapSpeedMps: meanGap };
}

/** hrTSS from a heart-rate stream, integrating IF^2 over time. */
function hrTssFromStream(hr: number[], time: number[] | undefined, lthr: number): number {
  const times = time && time.length === hr.length ? time : hr.map((_, i) => i);
  const dts = timeDeltas(times);
  let acc = 0;
  for (let i = 0; i < hr.length; i++) {
    const intensity = ifFromHrFraction(hr[i] / lthr);
    acc += intensity * intensity * dts[i];
  }
  return (100 * acc) / SECONDS_PER_HOUR;
}

/**
 * Banister TRIMP for an activity (exposed as an auxiliary metric). Requires
 * avg HR plus max/resting HR references. Sex-specific weighting.
 */
export function banisterTrimp(activity: Activity, profile: AthleteProfile): number | null {
  const avgHr = activity.averageHeartrate;
  const maxHr = resolveMaxHr(profile);
  if (!avgHr || !maxHr) return null;
  const rest = resolveRestingHr(profile);
  if (maxHr <= rest) return null;
  const hrr = clamp((avgHr - rest) / (maxHr - rest), 0, 1);
  const durationMin = activity.movingTime / 60;
  const factor =
    profile.sex === 'female' ? 0.86 * Math.exp(1.67 * hrr) : 0.64 * Math.exp(1.92 * hrr);
  return durationMin * hrr * factor;
}

/**
 * Compute an activity's training load using the best available method:
 * rTSS (pace) -> hrTSS (heart rate) -> duration estimate. Always coherent in
 * TSS units so the daily-load series and PMC stay consistent.
 */
export function computeActivityLoad(activity: Activity, profile: AthleteProfile): LoadResult {
  const durationSec = activity.movingTime;
  const distanceM = activity.distance;
  const hours = durationSec / SECONDS_PER_HOUR;
  const base: LoadResult = { tss: 0, method: 'none', durationSec, distanceM };
  if (hours <= 0) return base;

  const threshold = resolveThresholdSpeed(profile);
  const trustPace = !activity.manual;

  // --- rTSS via streams (best) ---
  if (threshold && threshold > 0 && trustPace && activity.streams) {
    const ngp = computeNgp(activity.streams);
    if (ngp && ngp.ngpSpeedMps > 0) {
      const intensityFactor = ngp.ngpSpeedMps / threshold;
      return {
        ...base,
        method: 'rtss',
        intensityFactor,
        ngpSpeedMps: ngp.ngpSpeedMps,
        meanGapSpeedMps: ngp.meanGapSpeedMps,
        averageSpeedMps: activity.averageSpeed,
        averageHr: activity.averageHeartrate,
        tss: intensityFactor * intensityFactor * hours * 100,
      };
    }
  }

  // --- rTSS via average speed (no stream normalization) ---
  if (
    threshold &&
    threshold > 0 &&
    trustPace &&
    activity.averageSpeed &&
    activity.averageSpeed > 0
  ) {
    const intensityFactor = activity.averageSpeed / threshold;
    return {
      ...base,
      method: 'rtss',
      intensityFactor,
      averageSpeedMps: activity.averageSpeed,
      averageHr: activity.averageHeartrate,
      tss: intensityFactor * intensityFactor * hours * 100,
    };
  }

  // --- hrTSS ---
  const lthr = resolveLthr(profile);
  if (lthr && lthr > 0) {
    if (activity.streams?.heartrate && activity.streams.heartrate.length > 1) {
      const tss = hrTssFromStream(activity.streams.heartrate, activity.streams.time, lthr);
      return {
        ...base,
        method: 'hrtss',
        averageHr: mean(activity.streams.heartrate),
        tss,
      };
    }
    if (activity.averageHeartrate && activity.averageHeartrate > 0) {
      const intensityFactor = ifFromHrFraction(activity.averageHeartrate / lthr);
      return {
        ...base,
        method: 'hrtss',
        intensityFactor,
        averageHr: activity.averageHeartrate,
        tss: intensityFactor * intensityFactor * hours * 100,
      };
    }
  }

  // --- duration fallback (assume easy aerobic, IF ~0.65) ---
  const assumedIf = 0.65;
  return {
    ...base,
    method: 'duration',
    intensityFactor: assumedIf,
    averageSpeedMps: activity.averageSpeed,
    tss: assumedIf * assumedIf * hours * 100,
  };
}
