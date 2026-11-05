#!/usr/bin/env -S npx tsx
/**
 * Science golden exporter (issue #383, ADR 0010).
 *
 * Calls the REAL `packages/core` sports-science, planner, guardrail and safety
 * functions on fixed, deterministic fixtures and writes `{ input, output }`
 * cases to `contracts/science-golden/*.json`. Those files are the parity
 * contract for the Kotlin port of the engine: every Kotlin function must
 * reproduce `output` from nothing but `input` (numbers within 1e-6 relative).
 *
 *   pnpm science:golden          # regenerate the committed files
 *   pnpm science:golden --check  # regenerate in memory; exit 1 listing stale files
 *
 * Determinism: a pinned reference clock (STRIDE_NOW, default DEFAULT_NOW), a
 * seeded PRNG for every synthetic stream (never Math.random), recursively sorted
 * object keys, shortest round-trip number formatting and a trailing newline.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { repairPlan, validatePlan } from '../packages/core/src/coach/guardrail.ts';
import {
  buildPlanSkeleton,
  makeSession,
  proposeNextWorkout,
  resolveRaceDistanceM,
} from '../packages/core/src/coach/planner.ts';
import {
  classifierWarnings,
  DISCLAIMER,
  detectRedFlags,
  PARQ_QUESTIONS,
  screenReadiness,
  shouldHalt,
} from '../packages/core/src/coach/safety.ts';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../packages/core/src/fixtures.ts';
import {
  addDays,
  aerobicDecoupling,
  banisterTrimp,
  buildAcwrSeries,
  buildPmcSeries,
  computeActivityLoad,
  computeHrZones,
  computeNgp,
  computePaceZones,
  computeZones,
  ctlRampCap,
  efficiencyFactor,
  estimateAnchors,
  gradeAdjustFactor,
  predictRaceTimeSec,
  rampRatePerWeek,
  resolveLthr,
  resolveMaxHr,
  resolveRestingHr,
  resolveThresholdSpeed,
  thresholdSpeedFromVdot,
  timeInHrZones,
  toDailyLoads,
  trainingSpeeds,
  vdotFromEffort,
  zoneDistribution,
} from '../packages/core/src/science/index.ts';
import {
  Activity,
  type ActivityStreams,
  AthleteProfile,
  type CoachContext,
  type DailyLoad,
  type PlanPhase,
  type PlanWeek,
  RaceGoal,
  type TrainingPlan,
  type WorkoutSuggestion,
  type WorkoutType,
} from '../packages/schemas/src/index.ts';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const GOLDEN_DIR = path.join(ROOT, 'contracts', 'science-golden');
/** Reference clock used when STRIDE_NOW is unset (matches scripts/smoke.mjs). */
export const DEFAULT_NOW = '2026-07-09T00:00:00Z';
/** Bump when the file/case layout (not the numbers) changes incompatibly. */
const FORMAT_VERSION = 1;

// ---------------------------------------------------------------------------
// Deterministic JSON
// ---------------------------------------------------------------------------

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

function isPrimitive(v: unknown): boolean {
  return v === null || v === undefined || typeof v !== 'object';
}

function scalar(v: unknown, where: string): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error(`non-finite number at ${where}: ${v}`);
    return Object.is(v, -0) ? '0' : JSON.stringify(v);
  }
  if (typeof v === 'string' || typeof v === 'boolean') return JSON.stringify(v);
  throw new Error(`unsupported value at ${where}: ${typeof v}`);
}

/**
 * Stable JSON: object keys sorted recursively (code-unit order), `undefined`
 * object members omitted (=> absent/null in Kotlin), `undefined` array items
 * become `null`, arrays of scalars (and short scalar-only objects) kept on one
 * line, 2-space indentation.
 * Numbers use JS's shortest round-trip form, i.e. full double precision.
 */
export function stableStringify(value: unknown, indent = '', where = '$'): string {
  if (isPrimitive(value)) return scalar(value, where);
  const next = `${indent}  `;
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    if (value.every(isPrimitive)) {
      return `[${value.map((v, i) => scalar(v, `${where}[${i}]`)).join(', ')}]`;
    }
    const items = value.map((v, i) => `${next}${stableStringify(v, next, `${where}[${i}]`)}`);
    return `[\n${items.join(',\n')}\n${indent}]`;
  }
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort();
  if (keys.length === 0) return '{}';
  if (keys.every((k) => isPrimitive(obj[k]))) {
    const inline = `{${keys.map((k) => `${JSON.stringify(k)}: ${scalar(obj[k], `${where}.${k}`)}`).join(', ')}}`;
    if (indent.length + inline.length <= 100) return inline;
  }
  const members = keys.map(
    (k) => `${next}${JSON.stringify(k)}: ${stableStringify(obj[k], next, `${where}.${k}`)}`,
  );
  return `{\n${members.join(',\n')}\n${indent}}`;
}

// ---------------------------------------------------------------------------
// Case plumbing
// ---------------------------------------------------------------------------

interface GoldenCase {
  name: string;
  fn: string;
  input: Record<string, unknown>;
  output: unknown;
}

interface GoldenFile {
  file: string;
  description: string;
  functions: Record<string, string>;
  notes?: string[];
  cases: GoldenCase[];
}

/**
 * Record a case. The input is snapshotted to plain JSON BEFORE the call, and the
 * function receives a deep clone, so in-place mutation by core can't leak into
 * the recorded input.
 */
function run<I extends Record<string, unknown>>(
  cases: GoldenCase[],
  name: string,
  fn: string,
  input: I,
  call: (input: I) => unknown,
): void {
  const snapshot = JSON.parse(stableStringify(input)) as Record<string, unknown>;
  const output = call(structuredClone(input));
  cases.push({ name, fn, input: snapshot, output: output === undefined ? null : output });
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** mulberry32 — tiny seeded PRNG (uniform in [0, 1)). */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const r1 = (x: number) => Number(x.toFixed(1));
const r3 = (x: number) => Number(x.toFixed(3));

interface SynthOptions {
  seed: number;
  samples: number;
  /** Seconds between samples. */
  dtSec?: number;
  speedMps: number;
  /** Peak grade (fraction) of the sinusoidal terrain. */
  gradeAmp?: number;
  gradePeriodSec?: number;
  baseHr?: number;
  /** Total bpm drift over the run (cardiac drift). */
  hrDrift?: number;
  /** Uniform speed noise amplitude (m/s). */
  speedNoise?: number;
  /** Recording gap: time jumps by `jumpSec` before sample `atIndex`. */
  gap?: { atIndex: number; jumpSec: number };
  /** Standing stop: samples [from, to) have zero speed and moving=false. */
  stop?: { from: number; to: number };
  streams: Array<keyof ActivityStreams>;
}

/** Deterministic synthetic activity streams (rounded like a real device). */
function synthStreams(o: SynthOptions): ActivityStreams {
  const rand = mulberry32(o.seed);
  const dt = o.dtSec ?? 1;
  const gradeAmp = o.gradeAmp ?? 0;
  const period = o.gradePeriodSec ?? 240;
  const baseHr = o.baseHr ?? 145;
  const drift = o.hrDrift ?? 0;
  const noise = o.speedNoise ?? 0;
  const s = {
    time: [] as number[],
    distance: [] as number[],
    altitude: [] as number[],
    velocitySmooth: [] as number[],
    heartrate: [] as number[],
    gradeSmooth: [] as number[],
    cadence: [] as number[],
    moving: [] as boolean[],
  };
  let t = 0;
  let dist = 0;
  let alt = 250;
  for (let i = 0; i < o.samples; i++) {
    if (i > 0) t += dt;
    if (o.gap && i === o.gap.atIndex) t += o.gap.jumpSec;
    const stopped = !!o.stop && i >= o.stop.from && i < o.stop.to;
    // Quantize the terrain phase so it is pure integer arithmetic + one sin.
    const grade = gradeAmp * Math.sin((2 * Math.PI * (t % period)) / period);
    const jitter = (rand() - 0.5) * 2 * noise;
    const speed = stopped ? 0 : Math.max(0, o.speedMps * (1 - 1.5 * grade) + jitter);
    if (i > 0) {
      dist += speed * dt;
      alt += speed * dt * grade;
    }
    const hrNoise = Math.round((rand() - 0.5) * 4);
    const hr = stopped
      ? baseHr - 20
      : baseHr + grade * 150 + (drift * i) / Math.max(1, o.samples - 1) + hrNoise;
    s.time.push(t);
    s.distance.push(r1(dist));
    s.altitude.push(r1(alt));
    s.velocitySmooth.push(r3(speed));
    s.heartrate.push(Math.round(hr));
    s.gradeSmooth.push(r1(grade * 100));
    s.cadence.push(stopped ? 0 : 85 + Math.round(rand() * 4));
    s.moving.push(!stopped);
  }
  const out: ActivityStreams = {};
  for (const k of o.streams) (out as Record<string, unknown>)[k] = s[k as keyof typeof s];
  return out;
}

function last<T>(xs: T[] | undefined): T | undefined {
  return xs && xs.length > 0 ? xs[xs.length - 1] : undefined;
}

function mean(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

/** A schema-parsed activity built around a stream set (summary fields derived). */
function activityFrom(
  id: string,
  streams: ActivityStreams | undefined,
  over: Partial<Activity> = {},
): Activity {
  const time = streams?.time;
  const duration = time && time.length > 0 ? (last(time) ?? 0) - time[0] : 0;
  const distance = last(streams?.distance) ?? 0;
  const hr = streams?.heartrate;
  return Activity.parse({
    id,
    source: 'upload',
    sportType: 'run',
    name: id,
    startDate: '2026-07-01T06:00:00Z',
    startDateLocal: '2026-07-01T08:00:00',
    distance,
    movingTime: duration,
    elapsedTime: duration,
    averageSpeed: duration > 0 ? distance / duration : undefined,
    averageHeartrate: hr && hr.length > 0 ? Math.round(mean(hr)) : undefined,
    maxHeartrate: hr && hr.length > 0 ? Math.max(...hr) : undefined,
    hasHeartrate: !!hr && hr.length > 0,
    streams,
    ...over,
  });
}

/** Summary-only activity (no streams), schema-parsed so defaults are explicit. */
function summary(id: string, over: Partial<Activity>): Activity {
  return Activity.parse({
    id,
    source: 'upload',
    sportType: 'run',
    name: id,
    startDate: '2026-07-01T06:00:00Z',
    distance: 0,
    movingTime: 0,
    elapsedTime: 0,
    ...over,
  });
}

const profile = (p: Record<string, unknown>): AthleteProfile => AthleteProfile.parse(p);

const PROFILES = {
  demo: profile(DEMO_PROFILE),
  vdotOnly: profile({ id: 'vdot-only', vdot: 45, restingHr: 55, maxHr: 185 }),
  ageOnly: profile({ id: 'age-only', age: 40 }),
  lthrOnly: profile({ id: 'lthr-only', lthr: 160 }),
  female: profile({
    id: 'female',
    sex: 'female',
    maxHr: 192,
    restingHr: 52,
    lthr: 172,
    thresholdSpeedMps: 3.6,
  }),
  male: profile({ id: 'male', sex: 'male', maxHr: 190, restingHr: 48, thresholdSpeedMps: 4.1 }),
  empty: profile({ id: 'empty' }),
  beginner: profile({ id: 'beginner', experienceLevel: 'beginner', thresholdSpeedMps: 2.8 }),
};

const STREAMS = {
  flat: synthStreams({
    seed: 1,
    samples: 900,
    speedMps: 3.0,
    baseHr: 148,
    hrDrift: 6,
    speedNoise: 0.15,
    streams: ['time', 'distance', 'altitude', 'velocitySmooth', 'heartrate', 'cadence'],
  }),
  hillyAltitude: synthStreams({
    seed: 2,
    samples: 720,
    speedMps: 2.9,
    gradeAmp: 0.12,
    gradePeriodSec: 300,
    baseHr: 150,
    hrDrift: 8,
    speedNoise: 0.1,
    // No velocity/grade streams: speed from distance/time, grade from altitude.
    streams: ['time', 'distance', 'altitude', 'heartrate'],
  }),
  hillyGradeSmooth: synthStreams({
    seed: 3,
    samples: 600,
    speedMps: 3.1,
    gradeAmp: 0.08,
    gradePeriodSec: 180,
    baseHr: 152,
    speedNoise: 0.1,
    streams: ['time', 'distance', 'velocitySmooth', 'gradeSmooth', 'heartrate'],
  }),
  steepClamp: synthStreams({
    seed: 4,
    samples: 300,
    dtSec: 2,
    speedMps: 1.4,
    gradeAmp: 0.6, // beyond Minetti's +/-0.45 validity: exercises the clamp
    gradePeriodSec: 400,
    baseHr: 160,
    streams: ['time', 'distance', 'altitude', 'velocitySmooth', 'heartrate'],
  }),
  gapsAndStops: synthStreams({
    seed: 5,
    samples: 800,
    speedMps: 3.2,
    gradeAmp: 0.03,
    baseHr: 155,
    hrDrift: 5,
    speedNoise: 0.2,
    gap: { atIndex: 400, jumpSec: 95 },
    stop: { from: 200, to: 260 },
    streams: ['time', 'distance', 'altitude', 'velocitySmooth', 'heartrate', 'moving'],
  }),
  distanceTimeOnly: synthStreams({
    seed: 6,
    samples: 500,
    dtSec: 3,
    speedMps: 3.4,
    speedNoise: 0.3,
    streams: ['time', 'distance'],
  }),
  noTime: synthStreams({
    seed: 7,
    samples: 400,
    speedMps: 3.0,
    gradeAmp: 0.05,
    speedNoise: 0.1,
    streams: ['velocitySmooth', 'heartrate'],
  }),
  intervals: (() => {
    // 10 x (60 s hard / 60 s easy) with HR lagging the speed changes.
    const base = synthStreams({
      seed: 8,
      samples: 1200,
      speedMps: 3.0,
      baseHr: 150,
      speedNoise: 0.05,
      streams: ['time', 'distance', 'velocitySmooth', 'heartrate'],
    });
    const v = base.velocitySmooth!;
    const hr = base.heartrate!;
    const dist: number[] = [];
    let d = 0;
    for (let i = 0; i < v.length; i++) {
      const hard = Math.floor(i / 60) % 2 === 1;
      v[i] = r3(hard ? v[i] * 1.45 : v[i] * 0.85);
      hr[i] = Math.round(hr[i] + (hard ? Math.min(25, (i % 60) * 0.6) : -8));
      if (i > 0) d += v[i];
      dist.push(r1(d));
    }
    base.distance = dist;
    return base;
  })(),
  hrOnly: synthStreams({
    seed: 9,
    samples: 600,
    dtSec: 2,
    speedMps: 2.5,
    baseHr: 140,
    hrDrift: 10,
    streams: ['time', 'heartrate'],
  }),
  single: { time: [0], distance: [0], velocitySmooth: [2.5], heartrate: [120] },
  empty: {},
};

const DEMO = Activity.parse(demoActivity());
const HISTORY = demoHistory().map((a) => Activity.parse(a));

// Shared activities used by more than one file.
const ACT = {
  flat: activityFrom('flat-hr', STREAMS.flat),
  hilly: activityFrom('hilly-altitude', STREAMS.hillyAltitude, { sportType: 'trail_run' }),
  hillyGrade: activityFrom('hilly-grade-smooth', STREAMS.hillyGradeSmooth),
  steep: activityFrom('steep-clamp', STREAMS.steepClamp, { sportType: 'trail_run' }),
  gaps: activityFrom('gaps-and-stops', STREAMS.gapsAndStops, {
    // Moving time excludes the 60 s standing stop; elapsed includes it + the gap.
    movingTime: (last(STREAMS.gapsAndStops.time) ?? 0) - 60,
  }),
  distTime: activityFrom('distance-time-only', STREAMS.distanceTimeOnly),
  noTime: activityFrom('no-time-stream', STREAMS.noTime, {
    movingTime: 400,
    elapsedTime: 400,
    distance: 1200,
    averageSpeed: 3.0,
  }),
  intervals: activityFrom('intervals', STREAMS.intervals),
  treadmill: activityFrom('treadmill-hr-stream', STREAMS.hrOnly, {
    sportType: 'treadmill_run',
    trainer: true,
    distance: 3000,
    averageSpeed: 2.5,
  }),
  manualAvgHr: summary('manual-avg-hr', {
    source: 'manual',
    manual: true,
    distance: 8000,
    movingTime: 2700,
    elapsedTime: 2700,
    averageSpeed: 8000 / 2700,
    averageHeartrate: 150,
    maxHeartrate: 168,
    hasHeartrate: true,
  }),
  summaryOnly: summary('summary-only', {
    distance: 10000,
    movingTime: 3000,
    elapsedTime: 3100,
    averageSpeed: 10000 / 3000,
    averageHeartrate: 158,
    maxHeartrate: 176,
    hasHeartrate: true,
  }),
  noHrNoPace: summary('no-hr-no-pace', {
    sportType: 'treadmill_run',
    trainer: true,
    distance: 5000,
    movingTime: 1800,
    elapsedTime: 1800,
  }),
  zero: summary('zero-length', { distance: 0, movingTime: 0, elapsedTime: 0 }),
  single: activityFrom('single-sample', STREAMS.single, {
    movingTime: 60,
    elapsedTime: 60,
    distance: 150,
    averageSpeed: 2.5,
  }),
};

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

const PMC_DEFAULTS = { ctlDays: 42, atlDays: 7, seedCtl: 0, seedAtl: 0 };
const ACWR_DEFAULTS = { acuteDays: 7, chronicDays: 28, warmupDays: 28 };

function loadFile(): GoldenFile {
  const cases: GoldenCase[] = [];

  for (const g of [-0.6, -0.45, -0.2, -0.1, -0.05, 0, 0.03, 0.05, 0.1, 0.2, 0.45, 0.6]) {
    run(cases, `grade ${g}`, 'gradeAdjustFactor', { gradeFraction: g }, (i) =>
      gradeAdjustFactor(i.gradeFraction),
    );
  }

  const ngpStreams: Array<[string, ActivityStreams]> = [
    ['demo rolling hills (fixtures.demoActivity)', DEMO.streams!],
    ['flat with noise', STREAMS.flat],
    ['hilly: speed from distance/time, grade from altitude', STREAMS.hillyAltitude],
    ['hilly: gradeSmooth stream (percent)', STREAMS.hillyGradeSmooth],
    ['steep terrain beyond +/-45% (grade clamp)', STREAMS.steepClamp],
    ['recording gap + standing stop', STREAMS.gapsAndStops],
    ['distance/time only, 3 s sampling', STREAMS.distanceTimeOnly],
    ['no time stream (index used as seconds)', STREAMS.noTime],
    ['intervals', STREAMS.intervals],
    ['heart rate only (no speed) -> null', STREAMS.hrOnly],
    ['single sample -> null', STREAMS.single],
    ['empty streams -> null', STREAMS.empty],
  ];
  for (const [name, streams] of ngpStreams) {
    run(cases, name, 'computeNgp', { streams }, (i) => computeNgp(i.streams));
  }

  const loadCases: Array<[string, Activity, AthleteProfile]> = [
    ['demo activity, demo profile -> rtss (streams)', DEMO, PROFILES.demo],
    ['flat, demo profile -> rtss (streams)', ACT.flat, PROFILES.demo],
    ['hilly altitude, threshold from vdot -> rtss', ACT.hilly, PROFILES.vdotOnly],
    ['hilly gradeSmooth, female profile -> rtss', ACT.hillyGrade, PROFILES.female],
    ['steep clamp -> rtss', ACT.steep, PROFILES.demo],
    ['gaps and stops -> rtss', ACT.gaps, PROFILES.male],
    ['intervals -> rtss', ACT.intervals, PROFILES.demo],
    ['summary only -> rtss (average speed)', ACT.summaryOnly, PROFILES.demo],
    ['hr only streams, no threshold -> hrtss (stream)', ACT.flat, PROFILES.lthrOnly],
    ['gaps and stops, no threshold -> hrtss (stream, moving mask)', ACT.gaps, PROFILES.lthrOnly],
    [
      'no moving stream, elapsed > moving -> hrtss scaled by moving/elapsed',
      { ...ACT.flat, id: 'flat-elapsed', movingTime: 800, elapsedTime: 899 },
      PROFILES.ageOnly,
    ],
    ['treadmill with hr stream -> hrtss (pace untrusted)', ACT.treadmill, PROFILES.demo],
    ['manual with avg hr -> hrtss (average)', ACT.manualAvgHr, PROFILES.demo],
    ['summary only, lthr from age -> hrtss (average)', ACT.summaryOnly, PROFILES.ageOnly],
    ['treadmill, no HR -> duration fallback', ACT.noHrNoPace, PROFILES.demo],
    ['summary only, empty profile -> duration fallback', ACT.summaryOnly, PROFILES.empty],
    ['zero-length activity -> none', ACT.zero, PROFILES.demo],
    ['single-sample streams -> rtss (average speed)', ACT.single, PROFILES.demo],
    ['no time stream -> rtss (streams)', ACT.noTime, PROFILES.demo],
  ];
  for (const [name, activity, prof] of loadCases) {
    run(cases, name, 'computeActivityLoad', { activity, profile: prof }, (i) =>
      computeActivityLoad(i.activity, i.profile),
    );
  }

  const trimpCases: Array<[string, Activity, AthleteProfile]> = [
    ['male weighting', ACT.summaryOnly, PROFILES.male],
    ['female weighting', ACT.summaryOnly, PROFILES.female],
    ['unspecified sex uses male weighting', ACT.flat, PROFILES.demo],
    ['maxHr from age, resting default 60', ACT.manualAvgHr, PROFILES.ageOnly],
    [
      'hrr clamped to 1 (avg above max)',
      { ...ACT.summaryOnly, averageHeartrate: 200 },
      PROFILES.male,
    ],
    ['no average HR -> null', ACT.noHrNoPace, PROFILES.demo],
    ['no max HR reference -> null', ACT.summaryOnly, PROFILES.lthrOnly],
    ['maxHr <= resting -> null', ACT.summaryOnly, profile({ id: 'odd', maxHr: 50, restingHr: 60 })],
  ];
  for (const [name, activity, prof] of trimpCases) {
    run(cases, name, 'banisterTrimp', { activity, profile: prof }, (i) =>
      banisterTrimp(i.activity, i.profile),
    );
  }

  return {
    file: 'load.json',
    description: 'Per-activity training load: NGP, rTSS/hrTSS/duration fallback chain, TRIMP.',
    functions: {
      gradeAdjustFactor:
        'packages/core/src/science/util.ts (Minetti cost ratio; helper used by NGP)',
      computeNgp: 'packages/core/src/science/load.ts',
      computeActivityLoad: 'packages/core/src/science/load.ts',
      banisterTrimp: 'packages/core/src/science/load.ts',
    },
    notes: [
      'computeNgp returns null when fewer than 2 speed samples can be derived.',
      'computeActivityLoad output omits absent optional fields (intensityFactor, ngpSpeedMps, meanGapSpeedMps, averageSpeedMps, averageHr); treat absent as null.',
      'computeActivityLoad does NOT round; tss/intensityFactor are full precision.',
    ],
    cases,
  };
}

function pmcFile(): GoldenFile {
  const cases: GoldenCase[] = [];

  const multiPerDay: Activity[] = [
    { ...ACT.summaryOnly, id: 'am-run', startDateLocal: '2026-06-01T07:00:00' },
    { ...ACT.noHrNoPace, id: 'pm-treadmill', startDateLocal: '2026-06-01T18:00:00' },
    { ...ACT.flat, id: 'utc-only', startDate: '2026-06-03T23:30:00Z', startDateLocal: undefined },
    { ...ACT.zero, id: 'zero', startDate: '2026-06-03T05:00:00Z', startDateLocal: undefined },
    { ...ACT.manualAvgHr, id: 'manual', startDateLocal: '2026-05-30T09:00:00' },
  ];
  const dailyCases: Array<[string, Activity[], AthleteProfile]> = [
    ['demo history (6 weeks)', HISTORY, PROFILES.demo],
    [
      'multiple activities per day; method rank; startDate fallback; unsorted input',
      multiPerDay,
      PROFILES.demo,
    ],
    ['no activities', [], PROFILES.demo],
  ];
  const dailies: Record<string, DailyLoad[]> = {};
  for (const [name, activities, prof] of dailyCases) {
    run(cases, name, 'toDailyLoads', { activities, profile: prof }, (i) => {
      const out = toDailyLoads(i.activities, i.profile);
      dailies[name] = out;
      return out;
    });
  }
  const demoDailies = toDailyLoads(HISTORY, PROFILES.demo);

  // A sparse, spiky synthetic series that trips every ACWR flag after warm-up.
  const rand = mulberry32(42);
  const spiky: DailyLoad[] = [];
  for (let d = 0; d < 70; d++) {
    const date = new Date(Date.UTC(2026, 0, 1) + d * 86_400_000).toISOString().slice(0, 10);
    let tss = d % 7 === 6 ? 0 : 30 + Math.round(rand() * 40);
    if (d >= 49 && d < 56) tss = 140 + Math.round(rand() * 30); // spike week
    if (d >= 60) tss = d % 3 === 0 ? 10 : 0; // detraining
    if (d % 11 === 5) continue; // missing days (gaps in the daily series)
    spiky.push({ date, tss, durationSec: 0, distanceM: 0, method: 'rtss', activityIds: [] });
  }

  const pmcCases: Array<[string, DailyLoad[], Record<string, unknown>]> = [
    ['demo history, defaults', demoDailies, { ...PMC_DEFAULTS }],
    [
      'demo history projected 21 days past last activity',
      demoDailies,
      {
        ...PMC_DEFAULTS,
        throughDate: '2026-07-26',
      },
    ],
    [
      'throughDate before last activity truncates the series',
      demoDailies,
      {
        ...PMC_DEFAULTS,
        throughDate: '2026-06-01',
      },
    ],
    ['seeded CTL/ATL', demoDailies, { ...PMC_DEFAULTS, seedCtl: 45, seedAtl: 60 }],
    ['custom time constants', spiky, { ctlDays: 28, atlDays: 5, seedCtl: 0, seedAtl: 0 }],
    ['spiky series with missing days', spiky, { ...PMC_DEFAULTS }],
    ['single day', [demoDailies[0]], { ...PMC_DEFAULTS, throughDate: '2026-06-01' }],
    ['empty series', [], { ...PMC_DEFAULTS }],
  ];
  const pmcSeries: Record<string, ReturnType<typeof buildPmcSeries>> = {};
  for (const [name, dailyLoads, options] of pmcCases) {
    run(cases, name, 'buildPmcSeries', { dailyLoads, options }, (i) => {
      const out = buildPmcSeries(i.dailyLoads, i.options);
      pmcSeries[name] = out;
      return out;
    });
  }

  const acwrCases: Array<[string, DailyLoad[], Record<string, unknown>]> = [
    ['demo history, defaults', demoDailies, { ...ACWR_DEFAULTS }],
    [
      'demo history projected 21 days',
      demoDailies,
      {
        ...ACWR_DEFAULTS,
        throughDate: '2026-07-26',
      },
    ],
    [
      'throughDate before last activity truncates the series',
      demoDailies,
      { ...ACWR_DEFAULTS, throughDate: '2026-06-20' },
    ],
    ['spiky series: low/ok/high/very_high flags', spiky, { ...ACWR_DEFAULTS }],
    ['spiky series, no warm-up suppression', spiky, { ...ACWR_DEFAULTS, warmupDays: 0 }],
    ['custom windows', spiky, { acuteDays: 5, chronicDays: 21, warmupDays: 10 }],
    [
      'fewer days than acute window (seed = mean over the available calendar days)',
      demoDailies.slice(0, 3),
      {
        ...ACWR_DEFAULTS,
      },
    ],
    ['empty series', [], { ...ACWR_DEFAULTS }],
  ];
  for (const [name, dailyLoads, options] of acwrCases) {
    run(cases, name, 'buildAcwrSeries', { dailyLoads, options }, (i) =>
      buildAcwrSeries(i.dailyLoads, i.options),
    );
  }

  const demoPmc = pmcSeries['demo history, defaults'];
  for (const [name, series, weeks] of [
    ['demo PMC, 1 week', demoPmc, 1],
    ['demo PMC, 2 weeks', demoPmc, 2],
    ['short series (clamped look-back)', demoPmc.slice(0, 4), 2],
    ['single point -> 0', demoPmc.slice(0, 1), 1],
  ] as const) {
    run(cases, name, 'rampRatePerWeek', { series, weeks }, (i) =>
      rampRatePerWeek(i.series, i.weeks),
    );
  }
  for (const experience of ['beginner', 'intermediate', 'advanced'] as const) {
    run(cases, experience, 'ctlRampCap', { experience }, (i) => ctlRampCap(i.experience));
  }

  return {
    file: 'pmc.json',
    description: 'Daily load aggregation, PMC (CTL/ATL/TSB) and EWMA ACWR series.',
    functions: {
      toDailyLoads: 'packages/core/src/science/pmc.ts',
      buildPmcSeries: 'packages/core/src/science/pmc.ts',
      buildAcwrSeries: 'packages/core/src/science/acwr.ts',
      rampRatePerWeek: 'packages/core/src/science/pmc.ts',
      ctlRampCap: 'packages/core/src/science/acwr.ts',
    },
    notes: [
      'Dates are YYYY-MM-DD keys; toDailyLoads uses startDateLocal (else startDate), first 10 chars, no timezone math.',
      'buildPmcSeries/buildAcwrSeries emit one point per calendar day from the first daily load to throughDate when given (extending past OR truncating before the last load), else to the last load, filling missing days with 0 TSS.',
      'PMC ctl/atl/tsb and ACWR acwr/acuteLoad/chronicLoad are rounded with toFixed(2) per point, but the EWMA state carries full precision between days.',
      'PMC alpha = 1 - exp(-1/N); ACWR lambda = 2/(N+1), both EWMAs seeded with the mean TSS of the first acuteDays CALENDAR DAYS of the series (missing days = 0; never past the series end).',
      'options objects list every default explicitly; throughDate is optional (absent = no projection).',
    ],
    cases,
  };
}

function zonesFile(): GoldenFile {
  const cases: GoldenCase[] = [];
  for (const [name, lthr, maxHr] of [
    ['lthr 165, maxHr 188', 165, 188],
    ['lthr 172, maxHr 192', 172, 192],
    ['no maxHr -> top = 1.15 x lthr', 160, undefined],
    ['maxHr <= lthr -> top = 1.15 x lthr', 170, 165],
    ['fractional lthr (from age)', 0.9 * (208 - 0.7 * 40), 208 - 0.7 * 40],
  ] as const) {
    run(cases, name, 'computeHrZones', { lthr, maxHr }, (i) => computeHrZones(i.lthr, i.maxHr));
  }
  for (const v of [3.33, 3.6, 2.8, thresholdSpeedFromVdot(45)]) {
    run(cases, `threshold ${v} m/s`, 'computePaceZones', { thresholdSpeedMps: v }, (i) =>
      computePaceZones(i.thresholdSpeedMps),
    );
  }
  for (const [name, prof] of Object.entries(PROFILES)) {
    run(cases, `profile ${name}`, 'computeZones', { profile: prof }, (i) =>
      computeZones(i.profile),
    );
  }

  const demoZones = computeHrZones(165, 188);
  const tihr: Array<[string, ActivityStreams, ReturnType<typeof computeHrZones>]> = [
    ['demo activity', DEMO.streams!, demoZones],
    ['flat run', STREAMS.flat, demoZones],
    ['intervals', STREAMS.intervals, demoZones],
    ['gaps and stops with moving mask', STREAMS.gapsAndStops, demoZones],
    ['no time stream', STREAMS.noTime, computeHrZones(160)],
    ['2 s sampling', STREAMS.hrOnly, computeHrZones(150, 180)],
  ];
  for (const [name, s, zones] of tihr) {
    run(
      cases,
      name,
      'timeInHrZones',
      { hr: s.heartrate!, time: s.time, zones, moving: s.moving },
      (i) => timeInHrZones(i.hr, i.time, i.zones, i.moving),
    );
  }
  run(
    cases,
    'bpm above top zone / below zero edge falls into last zone',
    'timeInHrZones',
    { hr: [250, 80, 140, 199, 300], time: [0, 5, 10, 12, 20], zones: demoZones },
    (i) => timeInHrZones(i.hr, i.time, i.zones),
  );
  run(
    cases,
    'time stream length mismatch -> index seconds',
    'timeInHrZones',
    { hr: [140, 150, 160, 170], time: [0, 10], zones: demoZones },
    (i) => timeInHrZones(i.hr, i.time, i.zones),
  );

  const zd: Array<[string, Activity[], AthleteProfile]> = [
    ['demo activity (HR stream path)', [DEMO], PROFILES.demo],
    ['demo history (summary averages path)', HISTORY, PROFILES.demo],
    ['intervals + gaps (HR stream, moving mask)', [ACT.intervals, ACT.gaps], PROFILES.demo],
    [
      'speed stream path (threshold only, no lthr)',
      [ACT.hilly, ACT.hillyGrade, ACT.gaps],
      profile({
        id: 'pace-only',
        thresholdSpeedMps: 3.3,
      }),
    ],
    [
      'average speed fallback',
      [ACT.summaryOnly, ACT.distTime],
      profile({
        id: 'pace-only',
        thresholdSpeedMps: 3.3,
      }),
    ],
    ['nothing known -> easy', [ACT.summaryOnly, ACT.noHrNoPace], PROFILES.empty],
    ['no activities -> zeros', [], PROFILES.demo],
  ];
  for (const [name, activities, prof] of zd) {
    run(cases, name, 'zoneDistribution', { activities, profile: prof }, (i) =>
      zoneDistribution(i.activities, i.profile),
    );
  }

  return {
    file: 'zones.json',
    description: 'HR and pace zones, time-in-zone and the polarized (easy/moderate/hard) split.',
    functions: {
      computeHrZones: 'packages/core/src/science/zones.ts',
      computePaceZones: 'packages/core/src/science/zones.ts',
      computeZones: 'packages/core/src/science/zones.ts',
      timeInHrZones: 'packages/core/src/science/zones.ts',
      zoneDistribution: 'packages/core/src/science/zones.ts',
    },
    notes: [
      'computeHrZones rounds bpm bounds with Math.round (JS: half rounds toward +Infinity).',
      'computePaceZones rounds speed bounds to 3 dp (toFixed(3)); top of zone 5 = 2.0 x threshold.',
      'timeInHrZones: zone match is minBpm <= bpm < maxBpm, else the LAST zone; keys are zone numbers as strings; seconds are unrounded.',
      'zoneDistribution rounds seconds with Math.round and percentages with toFixed(1).',
    ],
    cases,
  };
}

function vdotFile(): GoldenFile {
  const cases: GoldenCase[] = [];
  for (const [name, distanceM, timeSec] of [
    ['5k in 20:00', 5000, 1200],
    ['5k in 25:00', 5000, 1500],
    ['10k in 40:00', 10000, 2400],
    ['10k in 55:00', 10000, 3300],
    ['half in 1:30:00', 21097, 5400],
    ['marathon in 3:30:00', 42195, 12600],
    ['mile in 5:30', 1609.34, 330],
    ['zero distance -> 0', 0, 1200],
    ['zero time -> 0', 5000, 0],
  ] as const) {
    run(cases, name, 'vdotFromEffort', { distanceM, timeSec }, (i) =>
      vdotFromEffort(i.distanceM, i.timeSec),
    );
  }
  for (const vdot of [30, 40, 45, 50, 60, 70]) {
    for (const distanceM of [1500, 5000, 10000, 21097, 42195]) {
      run(cases, `vdot ${vdot} @ ${distanceM} m`, 'predictRaceTimeSec', { vdot, distanceM }, (i) =>
        predictRaceTimeSec(i.vdot, i.distanceM),
      );
    }
  }
  run(cases, 'vdot 0 -> 0', 'predictRaceTimeSec', { vdot: 0, distanceM: 5000 }, (i) =>
    predictRaceTimeSec(i.vdot, i.distanceM),
  );
  run(cases, 'distance 0 -> 0', 'predictRaceTimeSec', { vdot: 50, distanceM: 0 }, (i) =>
    predictRaceTimeSec(i.vdot, i.distanceM),
  );
  for (const vdot of [30, 40, 50, 60, 70, 85]) {
    run(cases, `vdot ${vdot}`, 'trainingSpeeds', { vdot }, (i) => trainingSpeeds(i.vdot));
    run(cases, `vdot ${vdot}`, 'thresholdSpeedFromVdot', { vdot }, (i) =>
      thresholdSpeedFromVdot(i.vdot),
    );
  }
  return {
    file: 'vdot.json',
    description: 'Jack Daniels VDOT model: VDOT from an effort, race prediction, training speeds.',
    functions: {
      vdotFromEffort: 'packages/core/src/science/vdot.ts',
      predictRaceTimeSec: 'packages/core/src/science/vdot.ts',
      trainingSpeeds: 'packages/core/src/science/vdot.ts',
      thresholdSpeedFromVdot: 'packages/core/src/science/vdot.ts',
    },
    notes: [
      'predictRaceTimeSec bisects time in [60, 21600] s for exactly 60 iterations and returns the midpoint; it is bounded at 6 h.',
      'trainingSpeeds/thresholdSpeedFromVdot return m/s (velocity in m/min divided by 60); E/M/T/I/R = 70/84/88/98/110 % of VDOT.',
      'Nothing here is rounded.',
    ],
    cases,
  };
}

function anchorsFile(): GoldenFile {
  const cases: GoldenCase[] = [];
  const mixed: Activity[] = [
    ACT.flat,
    ACT.intervals,
    { ...ACT.manualAvgHr, maxHeartrate: 199 }, // manual: HR counts, pace doesn't
    ACT.treadmill,
    summary('fast-but-short', { distance: 2500, movingTime: 540, elapsedTime: 540 }),
    summary('walk', { sportType: 'walk', distance: 6000, movingTime: 3600, elapsedTime: 3600 }),
    summary('hike-with-high-hr', {
      sportType: 'hike',
      distance: 9000,
      movingTime: 9000,
      elapsedTime: 9000,
      maxHeartrate: 201,
    }),
    summary('trail-race', {
      sportType: 'trail_run',
      distance: 15000,
      movingTime: 4500,
      elapsedTime: 4600,
    }),
  ];
  for (const [name, activities] of [
    ['demo history', HISTORY],
    ['demo activity', [DEMO]],
    ['mixed: manual/treadmill/short/non-run filtered for VDOT, HR from all', mixed],
    ['no qualifying runs, no HR -> {}', [ACT.zero, ACT.noHrNoPace]],
    ['no activities -> {}', []],
  ] as const) {
    run(cases, name, 'estimateAnchors', { activities: [...activities] }, (i) =>
      estimateAnchors(i.activities),
    );
  }
  for (const [name, prof] of Object.entries(PROFILES)) {
    run(cases, `profile ${name}`, 'resolveMaxHr', { profile: prof }, (i) =>
      resolveMaxHr(i.profile),
    );
    run(cases, `profile ${name}`, 'resolveLthr', { profile: prof }, (i) => resolveLthr(i.profile));
    run(cases, `profile ${name}`, 'resolveRestingHr', { profile: prof }, (i) =>
      resolveRestingHr(i.profile),
    );
    run(cases, `profile ${name}`, 'resolveThresholdSpeed', { profile: prof }, (i) =>
      resolveThresholdSpeed(i.profile),
    );
  }
  return {
    file: 'anchors.json',
    description: 'Physiological anchors: estimation from history and profile resolution chains.',
    functions: {
      estimateAnchors: 'packages/core/src/science/anchors.ts',
      resolveMaxHr: 'packages/core/src/science/anchors.ts',
      resolveLthr: 'packages/core/src/science/anchors.ts',
      resolveRestingHr: 'packages/core/src/science/anchors.ts',
      resolveThresholdSpeed: 'packages/core/src/science/anchors.ts',
    },
    notes: [
      'estimateAnchors rounds vdot toFixed(1), thresholdSpeedMps toFixed(3), lthr toFixed(0) (of 0.9 x max observed HR); absent keys mean unknown.',
      'VDOT candidates: sportType in {run, trail_run, treadmill_run}, not manual, not trainer, movingTime >= 600 s, distance > 0. Max HR is taken from ALL activities.',
      'resolve* return null (absent) when unknown, except resolveRestingHr which defaults to 60.',
      'Truthiness: profile fields equal to 0 are treated as missing (JS `if (x)`).',
    ],
    cases,
  };
}

function efficiencyFile(): GoldenFile {
  const cases: GoldenCase[] = [];
  const acts: Array<[string, Activity]> = [
    ['demo activity', DEMO],
    ['flat with drift', ACT.flat],
    ['hilly (grade from altitude)', ACT.hilly],
    ['hilly (gradeSmooth)', ACT.hillyGrade],
    ['steep clamp', ACT.steep],
    ['gap + stop', ACT.gaps],
    ['intervals', ACT.intervals],
    ['no time stream', ACT.noTime],
    ['hr-only streams (EF falls back to averageSpeed; decoupling undefined)', ACT.treadmill],
    ['summary only (no streams)', ACT.summaryOnly],
    ['no HR at all', ACT.noHrNoPace],
    [
      'HR stream but no averageHeartrate (EF uses stream mean)',
      { ...ACT.flat, id: 'no-avg-hr', averageHeartrate: undefined },
    ],
    ['too few samples', ACT.single],
    ['zero-length', ACT.zero],
  ];
  for (const [name, activity] of acts) {
    run(cases, name, 'efficiencyFactor', { activity }, (i) => efficiencyFactor(i.activity));
    run(cases, name, 'aerobicDecoupling', { activity }, (i) => aerobicDecoupling(i.activity));
  }
  return {
    file: 'efficiency.json',
    description: 'Efficiency Factor and aerobic decoupling (Pa:HR).',
    functions: {
      efficiencyFactor: 'packages/core/src/science/efficiency.ts',
      aerobicDecoupling: 'packages/core/src/science/efficiency.ts',
    },
    notes: [
      'Both return null (TS undefined) when not computable.',
      'efficiencyFactor = meanGap speed (m/s) x 60 / avg HR, rounded toFixed(2); avg HR prefers activity.averageHeartrate over the stream mean; speed prefers computeNgp(streams).meanGapSpeedMps over averageSpeed.',
      'aerobicDecoupling is a percentage rounded toFixed(1); halves split at time midpoint (times[i] < mid goes to the first half); needs >= 4 HR samples and a speed stream of the same length.',
    ],
    cases,
  };
}

function coachContext(over: Partial<CoachContext>, prof: AthleteProfile): CoachContext {
  return {
    generatedAt: '2026-07-09T00:00:00Z',
    profile: prof,
    recentActivities: [],
    ...over,
  };
}

function plannerFile(now: string): GoldenFile {
  const cases: GoldenCase[] = [];
  const recent = (tss: number, durationSec: number, distanceKm: number) => ({
    date: '2026-07-08',
    name: 'Run',
    sportType: 'run' as const,
    distanceKm,
    durationSec,
    tss,
    loadMethod: 'rtss' as const,
  });
  const pmcAt = (tsb: number) => ({ date: '2026-07-08', ctl: 40, atl: 40 - tsb, tsb });
  const dist = (hardPct: number) => ({
    easySec: 0,
    moderateSec: 0,
    hardSec: 0,
    easyPct: 100 - hardPct,
    moderatePct: 0,
    hardPct,
  });
  const acwr = (flag: 'low' | 'ok' | 'high' | 'very_high') => ({
    date: '2026-07-08',
    acwr: 1,
    acuteLoad: 40,
    chronicLoad: 40,
    flag,
  });
  const P = PROFILES.demo;
  const nextCases: Array<[string, CoachContext, AthleteProfile?]> = [
    ['very high ACWR -> recovery', coachContext({ acwr: acwr('very_high'), fitness: pmcAt(5) }, P)],
    ['deep fatigue TSB < -25 -> recovery', coachContext({ fitness: pmcAt(-30) }, P)],
    [
      'last session hard -> easy 45',
      coachContext({ fitness: pmcAt(0), recentActivities: [recent(90, 3600, 12)] }, P),
    ],
    [
      'fresh with little intensity -> threshold',
      coachContext({ fitness: pmcAt(-5), weeklyDistribution: dist(10) }, P),
    ],
    [
      'fresh, peak phase -> interval',
      coachContext({ fitness: pmcAt(2), weeklyDistribution: dist(5), planPhase: 'peak' }, P),
    ],
    [
      'enough intensity, no recent long -> long 80',
      coachContext(
        {
          fitness: pmcAt(-3),
          weeklyDistribution: dist(25),
          recentActivities: [recent(40, 3000, 9)],
        },
        P,
      ),
    ],
    [
      'enough intensity, recent long -> easy 50',
      coachContext(
        {
          fitness: pmcAt(-3),
          weeklyDistribution: dist(25),
          recentActivities: [recent(40, 3000, 9), recent(110, 6000, 16)],
        },
        P,
      ),
    ],
    [
      'tired-ish, enough intensity -> easy 50',
      coachContext({ fitness: pmcAt(-15), weeklyDistribution: dist(30) }, P),
    ],
    ['empty context, defaults (tsb 0, hardPct 0) -> threshold', coachContext({}, P)],
    [
      'threshold from vdot (recovery branch)',
      coachContext({ fitness: pmcAt(-30) }, PROFILES.vdotOnly),
      PROFILES.vdotOnly,
    ],
    ['no anchors -> default 3.0 m/s threshold', coachContext({}, PROFILES.empty), PROFILES.empty],
  ];
  for (const [name, context, prof = P] of nextCases) {
    run(cases, name, 'proposeNextWorkout', { context, profile: prof }, (i) =>
      proposeNextWorkout(i.context, i.profile),
    );
  }

  const start = now.slice(0, 10);
  const goal = (g: Record<string, unknown>) => RaceGoal.parse(g);
  const skeletons: Array<[string, AthleteProfile, RaceGoal, number]> = [
    ['10k, 8 weeks, demo profile', P, goal({ distance: '10k', name: 'Autumn 10k' }), 8],
    ['5k, 4 weeks, beginner', PROFILES.beginner, goal({ distance: '5k' }), 4],
    ['half, 12 weeks, vdot-only profile', PROFILES.vdotOnly, goal({ distance: 'half' }), 12],
    ['marathon, 16 weeks, female', PROFILES.female, goal({ distance: 'marathon' }), 16],
    [
      'custom 15k, 6 weeks, no anchors',
      PROFILES.empty,
      goal({
        distance: 'custom',
        customDistanceM: 15000,
      }),
      6,
    ],
    ['2 weeks (no taper/peak arithmetic edge)', P, goal({ distance: '10k' }), 2],
    ['1 week', P, goal({ distance: '5k' }), 1],
    [
      'race 10 weeks out, 8 requested: start shifts so week 8 ends on race day',
      P,
      goal({ distance: '10k', name: 'Autumn 10k', date: addDays(start, 69) }),
      8,
    ],
    [
      'race in 3.5 weeks, 8 requested: shrinks to 3 whole weeks',
      P,
      goal({ distance: '5k', date: addDays(start, 24) }),
      8,
    ],
    [
      'race goal as full ISO datetime',
      PROFILES.vdotOnly,
      goal({ distance: 'half', date: `${addDays(start, 83)}T07:30:00Z` }),
      12,
    ],
    [
      'marathon race day exempt from the long-run cap',
      PROFILES.female,
      goal({ distance: 'marathon', date: addDays(start, 111) }),
      16,
    ],
    [
      '2-week build with race: hard day 6 eased before the race',
      P,
      goal({ distance: '10k', date: addDays(start, 13) }),
      2,
    ],
    [
      'race before the start date: no alignment',
      P,
      goal({ distance: '10k', date: addDays(start, -3) }),
      4,
    ],
  ];
  for (const [name, prof, g, weeks] of skeletons) {
    const params = {
      profile: prof,
      goal: g,
      weeks,
      startDate: now,
      planId: `golden-${weeks}w`,
      createdAt: now,
    };
    run(cases, name, 'buildPlanSkeleton', { params }, (i) => buildPlanSkeleton(i.params));
  }

  const types: WorkoutType[] = [
    'easy',
    'long',
    'recovery',
    'tempo',
    'threshold',
    'interval',
    'repetition',
    'race',
    'rest',
    'cross_training',
  ];
  for (const type of types) {
    run(
      cases,
      `${type} 47.5 min @ 3.33`,
      'makeSession',
      { type, durationMin: 47.5, thresholdSpeedMps: 3.33, opts: { date: start } },
      (i) => makeSession(i.type, i.durationMin, i.thresholdSpeedMps, i.opts),
    );
  }
  run(
    cases,
    'zero threshold omits pace',
    'makeSession',
    { type: 'easy', durationMin: 30, thresholdSpeedMps: 0, opts: {} },
    (i) => makeSession(i.type as WorkoutType, i.durationMin, i.thresholdSpeedMps, i.opts),
  );
  run(
    cases,
    'custom rationale and description',
    'makeSession',
    {
      type: 'tempo',
      durationMin: 40,
      thresholdSpeedMps: 4.0,
      opts: { rationale: 'Because.', description: 'Custom.' },
    },
    (i) => makeSession(i.type as WorkoutType, i.durationMin, i.thresholdSpeedMps, i.opts),
  );
  for (const g of [
    { distance: '5k' },
    { distance: '10k' },
    { distance: 'half' },
    { distance: 'marathon' },
    { distance: 'custom', customDistanceM: 15000 },
    { distance: 'custom' },
  ]) {
    run(cases, JSON.stringify(g), 'resolveRaceDistanceM', { goal: goal(g) }, (i) =>
      resolveRaceDistanceM(i.goal),
    );
  }

  return {
    file: 'planner.json',
    description: 'Deterministic next-workout suggestion, periodized plan skeleton, session maker.',
    functions: {
      proposeNextWorkout: 'packages/core/src/coach/planner.ts',
      buildPlanSkeleton: 'packages/core/src/coach/planner.ts',
      makeSession: 'packages/core/src/coach/planner.ts',
      resolveRaceDistanceM: 'packages/core/src/coach/planner.ts',
    },
    notes: [
      `buildPlanSkeleton params.startDate/createdAt are the pinned reference clock (${now}); startDate is truncated to YYYY-MM-DD inside.`,
      'With goal.date on/after the start, buildPlanSkeleton aligns to the race (alignToRace): weeks = max(1, min(requested, floor((daysBetween(start, race)+1)/7))), start = race - (weeks*7-1); the last week day 7 is a race session (goal distance at threshold speed) and a hard day 6 becomes an easy run of the same length.',
      'Strings (titles, descriptions, rationales incl. interpolated numbers like "TSB -30") are part of the contract and must match exactly.',
      'makeSession: durationSec = Math.round(min*60); targetTss = toFixed(0) of IF^2*hours*100; pace = Math.round(1000/speed) s/km; distance = Math.round(speed*durationSec); pace is omitted (absent) when speed <= 0.',
      'proposeNextWorkout threshold: profile.thresholdSpeedMps, else from vdot, else 3.0 m/s.',
    ],
    cases,
  };
}

const T = 3.33;
type Entry = [number, WorkoutSuggestion];
const S = {
  rest: (d: number): Entry => [d, makeSession('rest', 0, T)],
  easy: (d: number, m: number): Entry => [d, makeSession('easy', m, T)],
  long: (d: number, m: number): Entry => [d, makeSession('long', m, T)],
  tempo: (d: number, m: number): Entry => [d, makeSession('tempo', m, T)],
  thr: (d: number, m: number): Entry => [d, makeSession('threshold', m, T)],
  int: (d: number, m: number): Entry => [d, makeSession('interval', m, T)],
  rec: (d: number, m: number): Entry => [d, makeSession('recovery', m, T)],
};

function week(weekNumber: number, phase: PlanPhase, entries: Entry[], withTss = false): PlanWeek {
  const days = entries.map(([day, s]) => ({ day, sessions: [s] }));
  const tss = days.reduce((a, d) => a + (d.sessions[0].targetTss ?? 0), 0);
  return {
    weekNumber,
    phase,
    focus: `${phase} week`,
    ...(withTss ? { targetTss: tss } : {}),
    days,
  };
}

function plan(id: string, weeks: PlanWeek[]): TrainingPlan {
  return {
    id,
    createdAt: '2026-07-09T00:00:00Z',
    goal: RaceGoal.parse({ distance: '10k' }),
    startDate: '2026-07-13',
    endDate: '2026-08-09',
    weeks,
  };
}

function guardrailFile(now: string): GoldenFile {
  const cases: GoldenCase[] = [];
  const healthyWeek = (n: number, phase: PlanPhase = 'base') =>
    week(n, phase, [
      S.rest(1),
      S.easy(2, 45),
      S.thr(3, 40),
      S.easy(4, 40),
      S.rest(5),
      S.easy(6, 40),
      S.long(7, 70),
    ]);
  const plans: Array<[string, TrainingPlan]> = [
    [
      'valid skeleton (10k, 8 weeks)',
      buildPlanSkeleton({
        profile: PROFILES.demo,
        goal: RaceGoal.parse({ distance: '10k' }),
        weeks: 8,
        startDate: now,
        planId: 'skeleton',
        createdAt: now,
      }),
    ],
    ['healthy hand-built two weeks', plan('healthy', [healthyWeek(1), healthyWeek(2)])],
    [
      'back-to-back hard days',
      plan('b2b', [
        week(1, 'build', [
          S.rest(1),
          S.thr(2, 50),
          S.int(3, 45),
          S.easy(4, 40),
          S.rest(5),
          S.tempo(6, 40),
          S.long(7, 75),
        ]),
      ]),
    ],
    [
      'no rest day',
      plan('no-rest', [
        week(1, 'base', [
          S.easy(1, 30),
          S.easy(2, 45),
          S.thr(3, 40),
          S.easy(4, 40),
          S.easy(5, 35),
          S.easy(6, 40),
          S.long(7, 70),
        ]),
      ]),
    ],
    [
      'recovery runs do NOT count as rest',
      plan('recovery-rest', [
        week(1, 'base', [
          S.rec(1, 30),
          S.easy(2, 45),
          S.thr(3, 40),
          S.easy(4, 40),
          S.easy(5, 35),
          S.easy(6, 40),
          S.long(7, 70),
        ]),
      ]),
    ],
    [
      'long run over 45% of the week',
      plan('long-cap', [
        week(1, 'base', [S.rest(1), S.easy(2, 30), S.rest(3), S.easy(4, 30), S.long(7, 150)]),
      ]),
    ],
    [
      'TSS ramp > 35% between loading weeks (taper/recovery skipped)',
      plan('ramp', [
        healthyWeek(1),
        week(2, 'recovery', [S.rest(1), S.easy(2, 30), S.rest(3), S.easy(4, 30), S.long(7, 50)]),
        week(3, 'build', [
          S.rest(1),
          S.thr(2, 70),
          S.easy(3, 60),
          S.tempo(4, 60),
          S.rest(5),
          S.easy(6, 60),
          S.long(7, 110),
        ]),
      ]),
    ],
    [
      'week.targetTss overrides session sum for ramp',
      plan('cached-tss', [
        week(1, 'base', [S.rest(1), S.easy(2, 45), S.long(7, 60)], true),
        { ...healthyWeek(2), targetTss: 50 },
      ]),
    ],
    [
      'everything wrong at once',
      plan('all-bad', [
        week(1, 'base', [S.easy(1, 30), S.easy(2, 30), S.easy(3, 30), S.long(7, 60)]),
        week(2, 'build', [
          S.thr(1, 60),
          S.int(2, 60),
          S.tempo(3, 60),
          S.easy(4, 50),
          S.easy(5, 50),
          S.easy(6, 50),
          S.long(7, 180),
        ]),
      ]),
    ],
    [
      'week of daily recovery runs has no rest day',
      plan('all-recovery', [
        week(1, 'recovery', [
          S.rec(1, 30),
          S.rec(2, 30),
          S.rec(3, 30),
          S.rec(4, 30),
          S.rec(5, 30),
          S.rec(6, 30),
          S.rec(7, 30),
        ]),
      ]),
    ],
    [
      'hard day 7 then hard day 1 of the next week',
      plan('b2b-across-weeks', [
        week(1, 'build', [
          S.rest(1),
          S.easy(2, 45),
          S.easy(3, 40),
          S.easy(4, 40),
          S.rest(5),
          S.easy(6, 40),
          S.int(7, 50),
        ]),
        week(2, 'build', [
          S.thr(1, 50),
          S.easy(2, 45),
          S.easy(3, 40),
          S.rest(4),
          S.easy(5, 40),
          S.rest(6),
          S.long(7, 70),
        ]),
      ]),
    ],
    [
      'race day is exempt from the long-run cap',
      plan('race-week', [
        week(1, 'taper', [
          S.rest(1),
          S.thr(2, 30),
          S.easy(3, 30),
          S.rest(4),
          S.easy(5, 25),
          S.rest(6),
          [7, makeSession('race', 210, T)],
        ]),
      ]),
    ],
    ['empty plan', plan('empty', [])],
  ];

  const ctxs: Array<[string, Record<string, unknown>]> = [
    ['cold start (TSS ratio ramp)', { experienceLevel: 'intermediate' }],
    ['seeded CTL ramp, beginner', { seedCtl: 20, seedAtl: 25, experienceLevel: 'beginner' }],
    ['seeded CTL ramp, advanced', { seedCtl: 55, seedAtl: 50, experienceLevel: 'advanced' }],
  ];
  for (const [pname, p] of plans) {
    for (const [cname, ctx] of ctxs) {
      run(cases, `${pname} / ${cname}`, 'validatePlan', { plan: p, ctx }, (i) =>
        validatePlan(i.plan, i.ctx),
      );
      if (cname.includes('advanced')) continue; // keep the file size in check
      run(cases, `${pname} / ${cname}`, 'repairPlan', { plan: p, ctx }, (i) =>
        repairPlan(i.plan, i.ctx),
      );
    }
  }
  // Repaired plans re-validated (what the coach does after repair).
  for (const [pname, p] of plans.slice(2)) {
    const ctx = { experienceLevel: 'intermediate' as const };
    const repaired = repairPlan(p, ctx).plan;
    run(cases, `re-validate repaired: ${pname}`, 'validatePlan', { plan: repaired, ctx }, (i) =>
      validatePlan(i.plan, i.ctx),
    );
  }

  return {
    file: 'guardrail.json',
    description: 'Plan guardrail validator and deterministic repairer.',
    functions: {
      validatePlan: 'packages/core/src/coach/guardrail.ts',
      repairPlan: 'packages/core/src/coach/guardrail.ts',
    },
    notes: [
      'Constants: MAX_WEEKLY_RAMP=1.35, MIN_REST_DAYS=1, LONG_RUN_MAX_FRACTION=0.45, MIN_SESSION_MIN=10, MAX_REPAIR_ITERS=40, projection epoch 2000-01-01.',
      'ctx.seedCtl PRESENT switches the ramp rule to the CTL-rise cap (ctlRampCap by experienceLevel) projected through buildPmcSeries; ABSENT uses the week-over-week TSS ratio. experienceLevel defaults to intermediate; seedAtl to 0.',
      'Violation messages (incl. the "≤" character and toFixed(1) CTL numbers) are part of the contract.',
      'repairPlan returns {plan, fixed}; it never mutates its input and recomputes week targetTss/targetDistanceKm.',
      'Rest days are rest-only (a recovery run is not rest); back-to-back hard days are also checked across the week boundary (week N day 7 -> week N+1 day 1, repaired first by easing week N+1 day 1); race sessions are excluded from the long-run cap (both total and longest).',
    ],
    cases,
  };
}

function safetyFile(): GoldenFile {
  const cases: GoldenCase[] = [];
  run(cases, 'constant', 'PARQ_QUESTIONS', {}, () => PARQ_QUESTIONS);
  run(cases, 'constant', 'DISCLAIMER', {}, () => DISCLAIMER);
  const p = PROFILES.demo;
  const flagged = profile({
    id: 'flagged',
    medicalClearance: false,
    healthFlags: ['heart condition', 'bone or joint problem'],
  });
  const notes: Array<[string, Record<string, unknown>]> = [
    ['chest pain -> stop', { text: 'Felt some chest pain on the last hill' }],
    ['case-insensitive -> stop', { text: 'CHEST TIGHTNESS after intervals' }],
    ['shortness of breath -> stop', { text: 'I was short of breath at an easy pace' }],
    ['dizziness -> stop', { text: 'got dizzy near the end' }],
    ['fainted -> stop', { text: 'I nearly fainted at the finish' }],
    ['passed out -> stop', { text: 'passed out briefly after the race' }],
    ['blacked out -> stop', { text: 'blacked out for a second' }],
    ['palpitations -> stop', { text: 'heart palpitations during the tempo' }],
    ['bare palpitations -> stop', { text: 'Palpitations on the last rep' }],
    ['lightheaded -> stop', { text: 'felt lightheaded after the hill' }],
    ['chest feels tight -> stop', { text: 'my chest feels tight on climbs' }],
    ["can't breathe -> stop", { text: "I can't breathe properly when I push" }],
    ['heart racing -> stop', { text: 'heart was racing at rest' }],
    ['negative: tight hamstrings', { text: 'tight hamstrings after the long run' }],
    ['negative: heart rate talk', { text: 'heart rate stayed in zone 2' }],
    ['severe pain -> stop (no extra warning)', { text: 'severe pain in my knee' }],
    ['pain -> warning', { text: 'some pain in my left shin' }],
    ['injured -> warning', { text: 'I am injured, calf strain' }],
    ['soreness -> warning', { text: 'general soreness today' }],
    ['sick -> warning', { text: 'feeling sick' }],
    ['fever -> warning', { text: 'had a fever yesterday' }],
    ['negative: happy note', { text: 'Felt great, smooth and relaxed!' }],
    ['negative: word boundary ("painting" is not "pain")', { text: 'painting the fence' }],
    ['negative: "chest" alone', { text: 'Wore my new chest strap' }],
    ['empty text', { text: '' }],
    ['no text field', {}],
    ['profile health flags + no clearance', { text: 'all good', profile: flagged }],
    ['cleared profile adds nothing', { text: 'all good', profile: p }],
    ['very high ACWR -> load warning', { acwrFlag: 'very_high' }],
    ['high ACWR -> nothing', { acwrFlag: 'high' }],
    ['TSB -31 -> load warning', { tsb: -31 }],
    ['TSB -30 -> nothing (strict <)', { tsb: -30 }],
    [
      'everything at once',
      { text: 'chest pain and sore legs', profile: flagged, tsb: -40, acwrFlag: 'very_high' },
    ],
  ];
  for (const [name, input] of notes) {
    run(cases, name, 'detectRedFlags', { input }, (i) => detectRedFlags(i.input));
    run(cases, name, 'shouldHalt', { flags: detectRedFlags(input) }, (i) => shouldHalt(i.flags));
  }
  for (const [name, answers] of [
    ['all no -> cleared', [false, false, false, false, false, false, false]],
    ['chest pain yes', [false, true, false, false, false, false, false]],
    ['several yes', [true, false, true, true, false, true, false]],
    ['short answers array -> not cleared (incomplete)', [false, true]],
    ['empty answers -> not cleared (incomplete)', []],
    ['too many answers -> not cleared', [false, false, false, false, false, false, false, false]],
  ] as const) {
    run(cases, name, 'screenReadiness', { answers: [...answers] }, (i) =>
      screenReadiness(i.answers),
    );
  }
  for (const [name, labels] of [
    ['known labels', ['injury', 'sleep']],
    ['case/whitespace normalized + dedup', [' Injury ', 'INJURY', 'stress']],
    ['unknown ignored', ['banana', 'fatigue']],
    ['empty', []],
  ] as const) {
    run(cases, name, 'classifierWarnings', { labels: [...labels] }, (i) =>
      classifierWarnings(i.labels),
    );
  }
  return {
    file: 'safety.json',
    description:
      'Safety layer: PAR-Q questions, disclaimer, red-flag detection, readiness screening.',
    functions: {
      PARQ_QUESTIONS: 'packages/core/src/coach/safety.ts (constant; input is {})',
      DISCLAIMER: 'packages/core/src/coach/safety.ts (constant; input is {})',
      detectRedFlags: 'packages/core/src/coach/safety.ts',
      shouldHalt: 'packages/core/src/coach/safety.ts',
      screenReadiness: 'packages/core/src/coach/safety.ts',
      classifierWarnings: 'packages/core/src/coach/safety.ts',
    },
    notes: [
      'detectRedFlags keyword regexes are case-insensitive with \\b word boundaries; at most one stop keyword flag, and keyword warnings are only checked when there is no stop flag.',
      'Flag order: keyword, health (healthFlags), health (medicalClearance === false), load (ACWR very_high), load (tsb < -30).',
      'screenReadiness clears only a COMPLETE answer list (length === PARQ_QUESTIONS.length) of all "no"; a wrong-length list appends the "incomplete screening" health flag.',
    ],
    cases,
  };
}

// ---------------------------------------------------------------------------
// Generation / check
// ---------------------------------------------------------------------------

/** Generate every golden file in memory: { 'load.json': '<content>\n', ... }. */
export function generateGoldens(now: string = process.env.STRIDE_NOW ?? DEFAULT_NOW) {
  const files = [
    loadFile(),
    pmcFile(),
    zonesFile(),
    vdotFile(),
    anchorsFile(),
    efficiencyFile(),
    plannerFile(now),
    guardrailFile(now),
    safetyFile(),
  ];
  const out: Record<string, string> = {};
  for (const f of files) {
    const doc = {
      meta: {
        formatVersion: FORMAT_VERSION,
        generator: 'scripts/science-golden.mts',
        regenerate: 'pnpm science:golden',
        now,
        tolerance: { relative: 1e-6 },
        description: f.description,
        functions: f.functions,
        notes: f.notes ?? [],
        caseCount: f.cases.length,
      },
      cases: f.cases,
    };
    out[f.file] = `${stableStringify(doc as unknown as Json)}\n`;
  }
  return out;
}

/**
 * Relative tolerance for the staleness check. V8's `Math.pow`/`**` is not
 * bit-stable across Node majors (e.g. `x ** 0.25` differs by 1 ULP between
 * Node 22 and 24), so a byte-exact check would flap across the CI matrix. Any
 * real behaviour change moves numbers far more than this, and it is still five
 * orders of magnitude tighter than the 1e-6 Kotlin parity tolerance.
 */
export const CHECK_RELATIVE_TOLERANCE = 1e-12;

function numbersClose(a: number, b: number): boolean {
  if (a === b) return true;
  const scale = Math.max(Math.abs(a), Math.abs(b));
  return Math.abs(a - b) <= CHECK_RELATIVE_TOLERANCE * Math.max(scale, 1e-300);
}

/** Structural equality: exact for everything except numbers (see tolerance). */
export function sameGolden(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') return numbersClose(a, b);
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((x, i) => sameGolden(x, b[i]));
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const ak = Object.keys(ao).sort();
  const bk = Object.keys(bo).sort();
  return ak.length === bk.length && ak.every((k, i) => k === bk[i] && sameGolden(ao[k], bo[k]));
}

function sameContent(onDisk: string, generated: string): boolean {
  if (onDisk === generated) return true;
  try {
    return sameGolden(JSON.parse(onDisk), JSON.parse(generated));
  } catch {
    return false;
  }
}

/**
 * Names of golden files in `dir` that differ from (or are missing vs)
 * `generated`. Byte-identical files pass; otherwise the parsed JSON must match
 * exactly except for numbers, which may differ by CHECK_RELATIVE_TOLERANCE.
 */
export function staleFiles(generated: Record<string, string>, dir: string = GOLDEN_DIR): string[] {
  const stale: string[] = [];
  for (const [name, content] of Object.entries(generated)) {
    const file = path.join(dir, name);
    // Normalize CRLF so a Windows checkout with autocrlf doesn't read as stale.
    const onDisk = fs.existsSync(file)
      ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
      : null;
    if (onDisk === null || !sameContent(onDisk, content)) stale.push(name);
  }
  const expected = new Set(Object.keys(generated));
  if (fs.existsSync(dir)) {
    for (const name of fs.readdirSync(dir)) {
      if (name.endsWith('.json') && !expected.has(name)) stale.push(`${name} (unexpected)`);
    }
  }
  return stale.sort();
}

function main(argv: string[]): number {
  const check = argv.includes('--check');
  const generated = generateGoldens();
  if (check) {
    const stale = staleFiles(generated);
    if (stale.length > 0) {
      process.stderr.write(
        `Science goldens are stale (run \`pnpm science:golden\` and commit):\n${stale
          .map((s) => `  - contracts/science-golden/${s}`)
          .join('\n')}\n`,
      );
      return 1;
    }
    process.stderr.write(`Science goldens up to date (${Object.keys(generated).length} files).\n`);
    return 0;
  }
  fs.mkdirSync(GOLDEN_DIR, { recursive: true });
  for (const [name, content] of Object.entries(generated)) {
    fs.writeFileSync(path.join(GOLDEN_DIR, name), content);
  }
  process.stderr.write(
    `Wrote ${Object.keys(generated).length} files to contracts/science-golden/.\n`,
  );
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv.slice(2));
}
