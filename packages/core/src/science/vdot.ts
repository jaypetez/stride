/**
 * Jack Daniels VDOT model (Daniels-Gilbert equations). Used to derive a
 * threshold-speed anchor and training paces from a recent hard effort/race.
 */

const A = 0.000104;
const B = 0.182258;
const C = -4.6;

/** Fraction of VO2max sustainable for a race of the given duration (minutes). */
export function percentVo2Max(timeMin: number): number {
  return (
    0.8 + 0.1894393 * Math.exp(-0.012778 * timeMin) + 0.2989558 * Math.exp(-0.1932605 * timeMin)
  );
}

/** Oxygen cost (ml/kg/min) of running at velocity v (meters/minute). */
export function vo2FromVelocity(vMetersPerMin: number): number {
  return C + B * vMetersPerMin + A * vMetersPerMin * vMetersPerMin;
}

/** VDOT ("pseudo VO2max") from a race/effort of distanceM in timeSec. */
export function vdotFromEffort(distanceM: number, timeSec: number): number {
  if (distanceM <= 0 || timeSec <= 0) return 0;
  const timeMin = timeSec / 60;
  const vMetersPerMin = distanceM / timeMin;
  return vo2FromVelocity(vMetersPerMin) / percentVo2Max(timeMin);
}

/** Velocity (m/min) that costs a given percentage of VDOT. */
export function velocityAtPercentVo2(vdot: number, pct: number): number {
  const target = pct * vdot;
  const disc = B * B - 4 * A * (C - target);
  if (disc < 0) return 0;
  return (-B + Math.sqrt(disc)) / (2 * A);
}

/** Threshold running speed (m/s) from VDOT — the rTSS anchor (~88% VO2max). */
export function thresholdSpeedFromVdot(vdot: number): number {
  return velocityAtPercentVo2(vdot, 0.88) / 60;
}

export interface TrainingSpeeds {
  /** m/s for each Daniels intensity. */
  E: number;
  M: number;
  T: number;
  I: number;
  R: number;
}

/** Representative training speeds (m/s) for each Daniels intensity from VDOT. */
export function trainingSpeeds(vdot: number): TrainingSpeeds {
  const at = (pct: number) => velocityAtPercentVo2(vdot, pct) / 60;
  return {
    E: at(0.7),
    M: at(0.84),
    T: at(0.88),
    I: at(0.98),
    R: at(1.1),
  };
}

/** Predict a race time (seconds) for a distance from VDOT, via bisection. */
export function predictRaceTimeSec(vdot: number, distanceM: number): number {
  if (vdot <= 0 || distanceM <= 0) return 0;
  let lo = 60; // 1 min
  let hi = 6 * 3600; // 6 h
  for (let iter = 0; iter < 60; iter++) {
    const mid = (lo + hi) / 2;
    const v = vdotFromEffort(distanceM, mid);
    if (v > vdot)
      lo = mid; // too fast -> needs more time
    else hi = mid;
  }
  return (lo + hi) / 2;
}
