import type { Activity } from '@stride/schemas';
import { computeNgp, deriveGradeStream, deriveSpeedStream } from './load';
import { gradeAdjustFactor, mean, timeDeltas } from './util';

/**
 * Efficiency Factor = grade-adjusted speed (m/min) / average HR. Rising EF at a
 * fixed HR over weeks indicates improving aerobic fitness/economy.
 */
export function efficiencyFactor(activity: Activity): number | undefined {
  const s = activity.streams;
  const avgHr = activity.averageHeartrate ?? (s?.heartrate ? mean(s.heartrate) : undefined);
  if (!avgHr || avgHr <= 0) return undefined;
  let speedMps: number | undefined;
  if (s) {
    const ngp = computeNgp(s);
    speedMps = ngp?.meanGapSpeedMps;
  }
  speedMps ??= activity.averageSpeed;
  if (!speedMps || speedMps <= 0) return undefined;
  return Number(((speedMps * 60) / avgHr).toFixed(2));
}

/**
 * Aerobic decoupling (Pa:HR): compares efficiency of the first vs second half of
 * a run. <5% is good aerobic durability; >10% suggests building more base.
 * Returns a percentage, or undefined if HR/speed streams are unavailable.
 */
export function aerobicDecoupling(activity: Activity): number | undefined {
  const s = activity.streams;
  if (!s?.heartrate || s.heartrate.length < 4) return undefined;
  const speeds = deriveSpeedStream(s);
  const hr = s.heartrate;
  if (speeds.length !== hr.length) return undefined;
  const grades = deriveGradeStream(s, speeds.length);
  const times = s.time && s.time.length === speeds.length ? s.time : speeds.map((_, i) => i);
  const dts = timeDeltas(times);
  const totalTime = times[times.length - 1] - times[0];
  const midTime = times[0] + totalTime / 2;

  let gap1 = 0;
  let hr1 = 0;
  let gap2 = 0;
  let hr2 = 0;
  for (let i = 0; i < speeds.length; i++) {
    const gap = speeds[i] * gradeAdjustFactor(grades[i] ?? 0);
    if (times[i] < midTime) {
      gap1 += gap * dts[i];
      hr1 += hr[i] * dts[i];
    } else {
      gap2 += gap * dts[i];
      hr2 += hr[i] * dts[i];
    }
  }
  if (hr1 <= 0 || hr2 <= 0) return undefined;
  const ef1 = gap1 / hr1;
  const ef2 = gap2 / hr2;
  if (ef1 <= 0) return undefined;
  return Number((((ef1 - ef2) / ef1) * 100).toFixed(1));
}
