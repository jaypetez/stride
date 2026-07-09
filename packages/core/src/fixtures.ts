import type { Activity, ActivityStreams, AthleteProfile } from '@stride/schemas';

/**
 * Deterministic synthetic data so `stride analyze --demo` and the test suite can
 * run with no network and no credentials. NOT real Strava data.
 */

export const DEMO_PROFILE: AthleteProfile = {
  id: 'demo',
  name: 'Demo Runner',
  sex: 'unspecified',
  age: 35,
  weightKg: 70,
  units: 'metric',
  experienceLevel: 'intermediate',
  thresholdSpeedMps: 3.33, // ~5:00/km threshold pace
  lthr: 165,
  maxHr: 188,
  restingHr: 50,
  vdot: 50,
  goals: 'Run a strong 10k',
  injuryHistory: [],
  medicalClearance: true,
  healthFlags: [],
};

/**
 * Build a steady run with mild rolling hills. `intensityMps` sets the average
 * flat speed; a gentle sinusoidal grade and HR drift make the streams realistic.
 */
function buildStreams(durationSec: number, intensityMps: number, baseHr: number): ActivityStreams {
  const time: number[] = [];
  const distance: number[] = [];
  const altitude: number[] = [];
  const velocitySmooth: number[] = [];
  const heartrate: number[] = [];
  let dist = 0;
  let alt = 100;
  for (let t = 0; t <= durationSec; t++) {
    // Rolling grade: +/-4% on a slow sine wave.
    const grade = 0.04 * Math.sin(t / 120);
    // Runner holds effort, so speed dips slightly uphill.
    const speed = intensityMps * (1 - grade * 1.5);
    dist += speed;
    alt += speed * grade;
    // HR rises with effort and drifts up over time (cardiac drift).
    const drift = (t / durationSec) * 4;
    const hr = baseHr + grade * 120 + drift;
    time.push(t);
    distance.push(Number(dist.toFixed(1)));
    altitude.push(Number(alt.toFixed(1)));
    velocitySmooth.push(Number(speed.toFixed(3)));
    heartrate.push(Math.round(hr));
  }
  return { time, distance, altitude, velocitySmooth, heartrate };
}

export function demoActivity(): Activity {
  const durationSec = 2700; // 45 min
  const streams = buildStreams(durationSec, 2.75, 138); // easy aerobic effort
  const dist = streams.distance![streams.distance!.length - 1];
  const hrArr = streams.heartrate!;
  return {
    id: 'demo-activity',
    source: 'manual',
    sportType: 'run',
    name: 'Demo Rolling-Hills Run',
    startDate: '2026-07-08T06:00:00Z',
    startDateLocal: '2026-07-08T08:00:00',
    timezone: 'Europe/Nairobi',
    distance: dist,
    movingTime: durationSec,
    elapsedTime: durationSec,
    totalElevationGain: 120,
    averageSpeed: dist / durationSec,
    averageHeartrate: Math.round(hrArr.reduce((a, b) => a + b, 0) / hrArr.length),
    maxHeartrate: Math.max(...hrArr),
    hasHeartrate: true,
    trainer: false,
    manual: false,
    streams,
  };
}

/** A ~6-week history of runs for PMC/ACWR/plan demos (summaries, no streams). */
export function demoHistory(): Activity[] {
  const activities: Activity[] = [];
  const start = Date.UTC(2026, 4, 25); // 2026-05-25
  const pattern = [
    { km: 8, minPerKm: 5.5, hard: false },
    { km: 0, minPerKm: 0, hard: false }, // rest
    { km: 10, minPerKm: 5.3, hard: false },
    { km: 6, minPerKm: 4.6, hard: true },
    { km: 8, minPerKm: 5.5, hard: false },
    { km: 0, minPerKm: 0, hard: false }, // rest
    { km: 16, minPerKm: 5.7, hard: false },
  ];
  for (let day = 0; day < 42; day++) {
    const spec = pattern[day % 7];
    if (spec.km === 0) continue;
    const ms = start + day * 86_400_000;
    const dt = new Date(ms);
    const iso = dt.toISOString();
    const dateKey = iso.slice(0, 10);
    const distance = spec.km * 1000;
    const movingTime = Math.round(spec.km * spec.minPerKm * 60);
    const avgSpeed = distance / movingTime;
    const avgHr = spec.hard ? 172 : 148;
    activities.push({
      id: `demo-hist-${day}`,
      source: 'manual',
      sportType: 'run',
      name: spec.hard ? 'Tempo Run' : spec.km >= 14 ? 'Long Run' : 'Easy Run',
      startDate: iso,
      startDateLocal: `${dateKey}T08:00:00`,
      distance,
      movingTime,
      elapsedTime: movingTime,
      totalElevationGain: 30,
      averageSpeed: avgSpeed,
      averageHeartrate: avgHr,
      maxHeartrate: avgHr + 12,
      hasHeartrate: true,
      trainer: false,
      manual: false,
    });
  }
  return activities;
}
