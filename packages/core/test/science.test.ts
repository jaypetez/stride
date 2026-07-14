import type { Activity, AthleteProfile, DailyLoad } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { demoActivity, demoHistory } from '../src/fixtures';
import {
  aerobicDecoupling,
  banisterTrimp,
  buildAcwrSeries,
  buildPmcSeries,
  computeActivityLoad,
  computeActivityMetrics,
  computeHrZones,
  computePaceZones,
  efficiencyFactor,
  estimateAnchors,
  gradeAdjustFactor,
  latestAcwr,
  latestPmc,
  minettiCost,
  mpsToSecPerKm,
  predictRaceTimeSec,
  thresholdSpeedFromVdot,
  vdotFromEffort,
} from '../src/science/index';

/** A daily-load series of `days` consecutive days each carrying `tss`. */
function dailySeries(tssByDay: number[], startYmd = [2026, 0, 1]): DailyLoad[] {
  return tssByDay.map((tss, i) => ({
    date: new Date(Date.UTC(startYmd[0], startYmd[1], startYmd[2] + i)).toISOString().slice(0, 10),
    tss,
    durationSec: 3600,
    distanceM: 12000,
    method: 'rtss' as const,
    activityIds: [`a${i}`],
  }));
}

/** A constant-speed run with a constant-HR stream. */
function runWithHr(speedMps: number, hrBpm: number, durationSec: number): Activity {
  const time: number[] = [];
  const distance: number[] = [];
  const velocitySmooth: number[] = [];
  const heartrate: number[] = [];
  for (let t = 0; t <= durationSec; t++) {
    time.push(t);
    distance.push(t * speedMps);
    velocitySmooth.push(speedMps);
    heartrate.push(hrBpm);
  }
  return {
    id: 'hr',
    source: 'strava',
    sportType: 'run',
    name: 'HR run',
    startDate: '2026-07-01T06:00:00Z',
    distance: speedMps * durationSec,
    movingTime: durationSec,
    elapsedTime: durationSec,
    totalElevationGain: 0,
    averageSpeed: speedMps,
    averageHeartrate: hrBpm,
    hasHeartrate: true,
    trainer: false,
    manual: false,
    streams: { time, distance, velocitySmooth, heartrate },
  };
}

function constantRun(speedMps: number, durationSec: number): Activity {
  const time: number[] = [];
  const distance: number[] = [];
  const altitude: number[] = [];
  const velocitySmooth: number[] = [];
  for (let t = 0; t <= durationSec; t++) {
    time.push(t);
    distance.push(t * speedMps);
    altitude.push(100);
    velocitySmooth.push(speedMps);
  }
  return {
    id: 'const',
    source: 'manual',
    sportType: 'run',
    name: 'Constant',
    startDate: '2026-07-01T06:00:00Z',
    startDateLocal: '2026-07-01T06:00:00',
    distance: speedMps * durationSec,
    movingTime: durationSec,
    elapsedTime: durationSec,
    totalElevationGain: 0,
    averageSpeed: speedMps,
    hasHeartrate: false,
    trainer: false,
    manual: false,
    streams: { time, distance, altitude, velocitySmooth },
  };
}

describe('util / minetti', () => {
  it('flat cost of transport is ~3.6 and factor 1.0', () => {
    expect(minettiCost(0)).toBeCloseTo(3.6, 5);
    expect(gradeAdjustFactor(0)).toBeCloseTo(1, 5);
  });
  it('uphill costs more than flat', () => {
    expect(gradeAdjustFactor(0.1)).toBeGreaterThan(1);
  });
  it('pace conversion round-trips', () => {
    expect(mpsToSecPerKm(1000 / 300)).toBeCloseTo(300, 3);
  });
});

describe('vdot', () => {
  it('estimates a plausible VDOT for a 20:00 5k', () => {
    const vdot = vdotFromEffort(5000, 20 * 60);
    expect(vdot).toBeGreaterThan(46);
    expect(vdot).toBeLessThan(52);
  });
  it('derives a threshold speed and monotonic race predictions', () => {
    const vdot = 50;
    const ts = thresholdSpeedFromVdot(vdot);
    expect(ts).toBeGreaterThan(3);
    expect(ts).toBeLessThan(5);
    const t5k = predictRaceTimeSec(vdot, 5000);
    const t10k = predictRaceTimeSec(vdot, 10000);
    expect(t10k).toBeGreaterThan(t5k);
  });
});

describe('load / rTSS', () => {
  it('1 hour at threshold pace ≈ 100 TSS with IF ≈ 1.0', () => {
    const profile: AthleteProfile = { ...demoProfile(), thresholdSpeedMps: 3.33 };
    const run = constantRun(3.33, 3600);
    const load = computeActivityLoad(run, profile);
    expect(load.method).toBe('rtss');
    expect(load.intensityFactor).toBeCloseTo(1.0, 1);
    expect(load.tss).toBeGreaterThan(95);
    expect(load.tss).toBeLessThan(105);
  });
  it('falls back to duration when no anchors/HR exist', () => {
    const run = constantRun(3.0, 1800);
    const bare: AthleteProfile = {
      ...demoProfile(),
      thresholdSpeedMps: undefined,
      lthr: undefined,
      maxHr: undefined,
      age: undefined,
    };
    const load = computeActivityLoad({ ...run, averageSpeed: undefined, streams: undefined }, bare);
    expect(load.method).toBe('duration');
    expect(load.tss).toBeGreaterThan(0);
  });
});

describe('PMC + ACWR', () => {
  const daily: DailyLoad[] = [];
  for (let i = 0; i < 60; i++) {
    daily.push({
      date: new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10),
      tss: 100,
      durationSec: 3600,
      distanceM: 12000,
      method: 'rtss',
      activityIds: [`a${i}`],
    });
  }
  it('CTL climbs toward the steady load and ATL tracks it', () => {
    const pmc = buildPmcSeries(daily);
    const last = latestPmc(pmc)!;
    expect(last.ctl).toBeGreaterThan(70);
    expect(last.ctl).toBeLessThan(100);
    expect(last.atl).toBeGreaterThan(95);
  });
  it('constant load yields ACWR ≈ 1.0 (ok)', () => {
    const acwr = latestAcwr(buildAcwrSeries(daily))!;
    expect(acwr.acwr).toBeGreaterThan(0.9);
    expect(acwr.acwr).toBeLessThan(1.1);
    expect(acwr.flag).toBe('ok');
  });

  it('does NOT flag a fresh short history as very_high (cold-start regression)', () => {
    // A brand-new athlete training 100 TSS/day for two weeks. With a zero-seeded
    // EWMA and no warm-up the acute EWMA outruns the chronic one and this flagged
    // "very_high" from day 1 — a false injury alarm. It must now stay calm.
    const series = buildAcwrSeries(dailySeries(new Array(14).fill(100)));
    expect(series.every((p) => p.flag !== 'very_high')).toBe(true);
    expect(latestAcwr(series)?.flag).toBe('ok');
  });

  it('still flags a genuine load spike once warmed up', () => {
    // 30 days establishing a ~50 TSS/day chronic base, then a sharp 7-day
    // 200 TSS/day block — a real acute:chronic spike that should trip the
    // guardrail (acute outruns the slower chronic EWMA).
    const series = buildAcwrSeries(
      dailySeries([...new Array(30).fill(50), ...new Array(7).fill(200)]),
    );
    const last = latestAcwr(series)!;
    expect(last.acwr).toBeGreaterThan(1.4);
    expect(['high', 'very_high']).toContain(last.flag);
  });
});

describe('load methods (HR / TRIMP / treadmill)', () => {
  it('uses hrTSS when pace is untrusted but HR is present', () => {
    const run = runWithHr(3.0, 150, 3600);
    const profile: AthleteProfile = { ...demoProfile(), thresholdSpeedMps: undefined, lthr: 160 };
    const load = computeActivityLoad(run, profile);
    expect(load.method).toBe('hrtss');
    expect(load.tss).toBeGreaterThan(0);
  });

  it('does not trust treadmill pace for rTSS', () => {
    const profile: AthleteProfile = { ...demoProfile(), thresholdSpeedMps: 3.33 };
    const outdoor = { ...runWithHr(3.33, 150, 3600), trainer: false };
    const treadmill = { ...runWithHr(3.33, 150, 3600), trainer: true };
    expect(computeActivityLoad(outdoor, profile).method).toBe('rtss');
    // Treadmill belt-distance can't drive rTSS; falls back to HR.
    expect(computeActivityLoad(treadmill, profile).method).not.toBe('rtss');
  });

  it('computes Banister TRIMP with sex-specific weighting', () => {
    const profile: AthleteProfile = { ...demoProfile(), maxHr: 190, restingHr: 50 };
    // HRr = (148-50)/(190-50) = 0.70; 60 min male => ~103 TRIMP.
    const activity = { ...runWithHr(3.0, 148, 3600) };
    const trimp = banisterTrimp(activity, profile)!;
    expect(trimp).toBeGreaterThan(95);
    expect(trimp).toBeLessThan(115);
    // Female weighting uses different constants (0.86·e^(1.67·HRr)), so the
    // value differs at the same HRr — the sex-specific branch is exercised.
    const female = banisterTrimp(activity, { ...profile, sex: 'female' })!;
    expect(female).toBeGreaterThan(0);
    expect(female).not.toBeCloseTo(trimp, 1);
  });
});

describe('moving-stream handling (auto-pause)', () => {
  function hrStream(hrBpm: number, movingSec: number, totalSec: number, withMoving: boolean) {
    const time: number[] = [];
    const heartrate: number[] = [];
    const moving: boolean[] = [];
    for (let t = 0; t <= totalSec; t++) {
      time.push(t);
      heartrate.push(hrBpm);
      moving.push(t < movingSec);
    }
    return {
      id: 'hr-stops',
      source: 'strava',
      sportType: 'run',
      name: 'HR run',
      startDate: '2026-07-01T06:00:00Z',
      distance: 0,
      movingTime: movingSec,
      elapsedTime: totalSec,
      totalElevationGain: 0,
      averageHeartrate: hrBpm,
      hasHeartrate: true,
      trainer: false,
      manual: false,
      streams: withMoving ? { time, heartrate, moving } : { time, heartrate },
    } as Activity;
  }
  const hrProfile: AthleteProfile = { ...demoProfile(), thresholdSpeedMps: undefined, lthr: 160 };

  it('hrTSS integrates over moving time via the `moving` stream, not elapsed (bug 7)', () => {
    // 60-min HR stream but only the first 40 min are "moving" — load must match a
    // 40-min effort, not the full hour of elevated-HR-while-stopped.
    const stopped = computeActivityLoad(hrStream(150, 2400, 3600, true), hrProfile);
    const full40 = computeActivityLoad(hrStream(150, 2400, 2400, false), hrProfile);
    const full60 = computeActivityLoad(hrStream(150, 3600, 3600, false), hrProfile);
    expect(stopped.method).toBe('hrtss');
    expect(Math.abs(stopped.tss - full40.tss)).toBeLessThan(1);
    expect(stopped.tss).toBeLessThan(full60.tss * 0.8);
  });

  it('hrTSS scales elapsed→moving time when no `moving` stream is present (bug 7)', () => {
    // No per-sample moving flags, but movingTime (40m) < elapsedTime (60m): the
    // elapsed integral is scaled to moving time (consistent with avg-HR hrTSS).
    const scaled = computeActivityLoad(hrStream(150, 2400, 3600, false), hrProfile);
    const full40 = computeActivityLoad(hrStream(150, 2400, 2400, false), hrProfile);
    expect(Math.abs(scaled.tss - full40.tss)).toBeLessThan(1);
  });

  it('time-in-zone skips non-moving samples (bug 10)', () => {
    // HR 130 / LTHR 160 = 0.81 → easy bucket for the whole recording.
    const withMoving = computeActivityMetrics(hrStream(130, 2400, 3600, true), hrProfile);
    const withoutMoving = computeActivityMetrics(hrStream(130, 2400, 3600, false), hrProfile);
    // With the moving stream, only the ~40 moving minutes are counted as easy.
    expect(withMoving.zoneDistribution?.easySec).toBeGreaterThan(2300);
    expect(withMoving.zoneDistribution?.easySec).toBeLessThan(2500);
    // Without it, the 20 stopped minutes inflate the easy seconds toward 60 min.
    expect(withoutMoving.zoneDistribution?.easySec).toBeGreaterThan(3500);
  });
});

describe('efficiency factor & aerobic decoupling', () => {
  it('EF = grade-adjusted speed (m/min) / avg HR', () => {
    // 3.33 m/s = 199.8 m/min; / 150 bpm = 1.33.
    expect(efficiencyFactor(runWithHr(3.33, 150, 1800))).toBeCloseTo(1.33, 1);
  });

  it('a perfectly steady run has ~0% decoupling', () => {
    const dec = aerobicDecoupling(runWithHr(3.0, 150, 3600));
    expect(dec).toBeDefined();
    expect(Math.abs(dec!)).toBeLessThan(1);
  });
});

describe('PMC projection to a reference day', () => {
  it('fatigue (ATL) decays over rest days when projected past the last activity', () => {
    const daily = dailySeries(new Array(30).fill(100));
    const lastDate = daily[daily.length - 1].date;
    const atLast = latestPmc(buildPmcSeries(daily))!;
    // Project 14 rest days forward from the last activity.
    const projected = new Date(`${lastDate}T00:00:00Z`);
    projected.setUTCDate(projected.getUTCDate() + 14);
    const through = projected.toISOString().slice(0, 10);
    const atRest = latestPmc(buildPmcSeries(daily, { throughDate: through }))!;
    expect(atRest.atl).toBeLessThan(atLast.atl); // fatigue fell
    expect(atRest.tsb).toBeGreaterThan(atLast.tsb); // form rose
    expect(atRest.ctl).toBeLessThan(atLast.ctl); // fitness slowly decays too
  });
});

describe('zones', () => {
  it('HR zones are ordered and pinned to LTHR', () => {
    const zones = computeHrZones(165, 188);
    expect(zones).toHaveLength(5);
    expect(zones[0].minBpm).toBe(0);
    expect(zones[3].minBpm).toBeGreaterThan(zones[2].minBpm);
  });
  it('pace zones are ordered by speed', () => {
    const zones = computePaceZones(3.33);
    expect(zones[4].minSpeedMps).toBeGreaterThan(zones[0].minSpeedMps);
  });
});

describe('analysis of demo activity', () => {
  it('produces coherent metrics', () => {
    const m = computeActivityMetrics(demoActivity(), demoProfile());
    expect(m.tss).toBeGreaterThan(0);
    expect(m.method).toBe('rtss');
    expect(m.efficiencyFactor).toBeGreaterThan(0);
    expect(m.aerobicDecouplingPct).toBeDefined();
    expect(m.zoneDistribution).toBeDefined();
  });
});

describe('anchor estimation', () => {
  it('derives anchors from history', () => {
    const est = estimateAnchors(demoHistory());
    expect(est.vdot).toBeGreaterThan(0);
    expect(est.thresholdSpeedMps).toBeGreaterThan(0);
    expect(est.maxHr).toBeGreaterThan(0);
  });
});

function demoProfile(): AthleteProfile {
  return {
    id: 'demo',
    sex: 'unspecified',
    age: 35,
    units: 'metric',
    experienceLevel: 'intermediate',
    thresholdSpeedMps: 3.33,
    lthr: 165,
    maxHr: 188,
    restingHr: 50,
    injuryHistory: [],
    medicalClearance: true,
    healthFlags: [],
  };
}
