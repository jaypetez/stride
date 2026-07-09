import type { Activity, AthleteProfile, DailyLoad } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { demoActivity, demoHistory } from '../src/fixtures';
import {
  buildAcwrSeries,
  buildPmcSeries,
  computeActivityLoad,
  computeActivityMetrics,
  computeHrZones,
  computePaceZones,
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
