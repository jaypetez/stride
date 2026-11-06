import type {
  AthleteProfile,
  CoachContext,
  FitnessSeed,
  IntensityLabel,
  LlmPlanProposal,
  PlanDay,
  PlanPhase,
  PlanWeek,
  RaceGoal,
  TrainingPlan,
  WorkoutSuggestion,
  WorkoutType,
} from '@stride/schemas';
import {
  addDays,
  daysBetween,
  mpsToSecPerKm,
  resolveThresholdSpeed,
  thresholdSpeedFromVdot,
  toDateKey,
} from '../science/index';

const DEFAULT_THRESHOLD_MPS = 3.0; // ~5:33/km, a conservative default

export function resolveRaceDistanceM(goal: RaceGoal): number {
  switch (goal.distance) {
    case '5k':
      return 5000;
    case '10k':
      return 10_000;
    case 'half':
      return 21_097;
    case 'marathon':
      return 42_195;
    default:
      return goal.customDistanceM ?? 10_000;
  }
}

function thresholdOf(profile: AthleteProfile): number {
  return (
    resolveThresholdSpeed(profile) ??
    (profile.vdot ? thresholdSpeedFromVdot(profile.vdot) : DEFAULT_THRESHOLD_MPS)
  );
}

interface Intensity {
  overallIf: number;
  paceIf: number;
  hrZone: number;
  label?: IntensityLabel;
}

const INTENSITY: Record<WorkoutType, Intensity> = {
  easy: { overallIf: 0.68, paceIf: 0.75, hrZone: 2, label: 'E' },
  long: { overallIf: 0.7, paceIf: 0.72, hrZone: 2, label: 'E' },
  recovery: { overallIf: 0.6, paceIf: 0.65, hrZone: 1, label: 'E' },
  tempo: { overallIf: 0.85, paceIf: 0.9, hrZone: 3, label: 'M' },
  threshold: { overallIf: 0.88, paceIf: 0.97, hrZone: 4, label: 'T' },
  interval: { overallIf: 0.92, paceIf: 1.02, hrZone: 5, label: 'I' },
  repetition: { overallIf: 0.95, paceIf: 1.1, hrZone: 5, label: 'R' },
  race: { overallIf: 1.0, paceIf: 1.0, hrZone: 5, label: 'T' },
  rest: { overallIf: 0, paceIf: 0, hrZone: 1 },
  cross_training: { overallIf: 0.5, paceIf: 0, hrZone: 1 },
};

const QUALITY_TYPES = new Set<WorkoutType>([
  'tempo',
  'threshold',
  'interval',
  'repetition',
  'race',
]);

const TITLES: Record<WorkoutType, string> = {
  easy: 'Easy run',
  long: 'Long run',
  recovery: 'Recovery run',
  tempo: 'Tempo run',
  threshold: 'Threshold session',
  interval: 'Interval session',
  repetition: 'Repetition / strides',
  race: 'Race',
  rest: 'Rest day',
  cross_training: 'Cross-training',
};

export function makeSession(
  type: WorkoutType,
  durationMin: number,
  thresholdSpeedMps: number,
  opts: { date?: string; rationale?: string; description?: string } = {},
): WorkoutSuggestion {
  const it = INTENSITY[type];
  if (type === 'rest') {
    return {
      type,
      title: TITLES.rest,
      description: opts.description ?? 'Full rest. Recovery is when adaptation happens.',
      date: opts.date,
      targetDurationSec: 0,
      targetTss: 0,
      rationale: opts.rationale ?? 'Rest days let fitness consolidate and reduce injury risk.',
    };
  }
  const speed = thresholdSpeedMps * it.paceIf;
  const durationSec = Math.round(durationMin * 60);
  const tss = Number((it.overallIf * it.overallIf * (durationMin / 60) * 100).toFixed(0));
  // A non-rest type with `paceIf === 0` (e.g. cross_training) — or a degenerate
  // zero/negative threshold — has no meaningful running pace. Omit the pace
  // field entirely (never emit Infinity/NaN, which JSON-serializes to null and
  // then fails schema re-parse) and give it a pace-independent distance of 0.
  const paceSecPerKm = speed > 0 ? Math.round(mpsToSecPerKm(speed)) : undefined;
  const hasPace = paceSecPerKm !== undefined && Number.isFinite(paceSecPerKm);
  const distanceM = hasPace ? Math.round(speed * durationSec) : 0;
  return {
    type,
    label: it.label,
    title: `${TITLES[type]} (${Math.round(durationMin)} min)`,
    description: opts.description ?? describeType(type, durationMin),
    date: opts.date,
    targetDistanceM: distanceM,
    targetDurationSec: durationSec,
    ...(hasPace ? { targetPaceSecPerKm: paceSecPerKm } : {}),
    targetHrZone: it.hrZone,
    targetTss: tss,
    rationale: opts.rationale ?? defaultRationale(type),
  };
}

/**
 * Recover the athlete's threshold speed (m/s) from a session's own target pace,
 * inverting the `paceIf` scaling `makeSession` applied. Falls back to the
 * conservative default when the session carries no usable pace. Used to keep
 * every derived number computed in code when re-deriving a session.
 */
export function thresholdFromSession(session: WorkoutSuggestion): number {
  const it = INTENSITY[session.type];
  return session.targetPaceSecPerKm && it.paceIf > 0
    ? 1000 / session.targetPaceSecPerKm / it.paceIf
    : DEFAULT_THRESHOLD_MPS;
}

/**
 * Rebuild a session at a new duration, recovering the athlete's threshold speed
 * from the session's own target pace so load/distance are re-derived by
 * `makeSession` (every number stays computed in code). Used by the guardrail
 * repairer to shrink an oversized long run or scale a week's quality volume down
 * deterministically. Rest sessions are returned unchanged.
 */
export function rescaleSession(session: WorkoutSuggestion, durationMin: number): WorkoutSuggestion {
  if (session.type === 'rest') return session;
  const threshold = thresholdFromSession(session);
  return makeSession(session.type, durationMin, threshold, {
    date: session.date,
    rationale: session.rationale,
  });
}

function describeType(type: WorkoutType, durationMin: number): string {
  switch (type) {
    case 'easy':
      return `${Math.round(durationMin)} min at a conversational, aerobic pace.`;
    case 'long':
      return `${Math.round(durationMin)} min steady long run to build aerobic durability.`;
    case 'recovery':
      return `${Math.round(durationMin)} min very easy to promote recovery.`;
    case 'tempo':
      return `Warm up, then a sustained comfortably-hard tempo block, then cool down (~${Math.round(durationMin)} min total).`;
    case 'threshold':
      return `Warm up, then threshold intervals (e.g. 3-4 × 8 min at T pace, 90s jog), then cool down (~${Math.round(durationMin)} min total).`;
    case 'interval':
      return `Warm up, then VO2max intervals (e.g. 5-6 × 3 min hard, equal jog recovery), then cool down (~${Math.round(durationMin)} min total).`;
    case 'repetition':
      return `Warm up, then short fast reps / strides for economy (e.g. 8-10 × 30s), then cool down.`;
    default:
      return `${Math.round(durationMin)} min session.`;
  }
}

function defaultRationale(type: WorkoutType): string {
  if (QUALITY_TYPES.has(type)) {
    return 'Targeted intensity to drive the specific adaptation for this phase; keep the hard parts controlled.';
  }
  return 'Most weekly volume should be easy (the ~80/20 principle) to build aerobic fitness while staying fresh.';
}

// --- Next-workout suggestion (deterministic) ---

export function proposeNextWorkout(
  context: CoachContext,
  profile: AthleteProfile,
): WorkoutSuggestion {
  const threshold = thresholdOf(profile);
  const tsb = context.fitness?.tsb ?? 0;
  const acwrFlag = context.acwr?.flag ?? 'ok';
  const hardPct = context.weeklyDistribution?.hardPct ?? 0;
  const phase = context.planPhase;

  const last = context.recentActivities[0];
  const lastHard = last ? impliedIntensity(last.tss, last.durationSec) > 0.82 : false;
  const hasRecentLong = context.recentActivities.some((a) => a.distanceKm >= 14);

  if (acwrFlag === 'very_high' || tsb < -25) {
    return makeSession('recovery', 30, threshold, {
      rationale: `Your form (TSB ${round(tsb)}) and workload signals say back off — an easy recovery run keeps things ticking without adding fatigue.`,
    });
  }
  if (lastHard) {
    return makeSession('easy', 45, threshold, {
      rationale:
        'Your last session was hard, so keep ~48h between quality days: an easy run aids recovery and preserves the 80/20 balance.',
    });
  }
  if (hardPct < 18 && tsb > -12) {
    const type: WorkoutType = phase === 'peak' ? 'interval' : 'threshold';
    return makeSession(type, 50, threshold, {
      rationale: `You're reasonably fresh (TSB ${round(tsb)}) and only ${round(hardPct)}% of this week has been hard — a ${type} session nudges you toward the ~20% quality target.`,
    });
  }
  if (!hasRecentLong && tsb > -10) {
    return makeSession('long', 80, threshold, {
      rationale:
        'No long run in the last week — a steady long run develops aerobic durability, the backbone of distance fitness.',
    });
  }
  return makeSession('easy', 50, threshold, {
    rationale: `An easy aerobic run fits your current form (TSB ${round(tsb)}) and keeps the week's intensity distribution balanced.`,
  });
}

function impliedIntensity(tss: number, durationSec: number): number {
  const hours = durationSec / 3600;
  if (hours <= 0) return 0;
  return Math.sqrt(tss / (hours * 100));
}

// --- Plan skeleton (deterministic, periodized) ---

function phaseForWeek(week: number, weeks: number): PlanPhase {
  const taperWeeks = weeks >= 6 ? 2 : weeks >= 3 ? 1 : 0;
  const baseWeeks = Math.max(1, Math.round(weeks * 0.35));
  const peakWeeks = weeks >= 6 && weeks - taperWeeks - baseWeeks >= 2 ? 1 : 0;
  const buildEnd = weeks - taperWeeks - peakWeeks;
  if (week > weeks - taperWeeks) return 'taper';
  if (week > buildEnd) return 'peak';
  if (week > baseWeeks) return 'build';
  return 'base';
}

const FOCUS: Record<PlanPhase, string> = {
  base: 'Aerobic base — mostly easy volume, a light dose of intensity.',
  build: 'Build — threshold and tempo work on top of maintained volume.',
  peak: 'Peak — race-specific intensity (VO2max/threshold), volume holds.',
  taper: 'Taper — cut volume ~50%, keep a little intensity, arrive fresh.',
  recovery: 'Recovery week — reduced volume to absorb the last block.',
};

/** One slot of a weekly template: the day, the workout type and its minutes. */
interface TemplateSlot {
  day: number;
  type: WorkoutType;
  /** Template minutes; ignored for `long` (the progression sets it) and `rest`. */
  min: number;
}

/** A template per phase; `recovery` is the recovery week, whatever its phase. */
type TemplateKey = PlanPhase;

/**
 * The weekly templates. Unseeded plans use these minutes as-is; seeded plans
 * (MVP-025) scale every non-long running session by one per-week factor.
 */
const TEMPLATES: Record<TemplateKey, TemplateSlot[]> = {
  recovery: [
    { day: 1, type: 'rest', min: 0 },
    { day: 2, type: 'easy', min: 35 },
    { day: 3, type: 'easy', min: 40 },
    { day: 4, type: 'rest', min: 0 },
    { day: 5, type: 'easy', min: 30 },
    { day: 6, type: 'rest', min: 0 },
    { day: 7, type: 'long', min: 0 },
  ],
  build: [
    { day: 1, type: 'rest', min: 0 },
    { day: 2, type: 'threshold', min: 50 },
    { day: 3, type: 'easy', min: 45 },
    { day: 4, type: 'easy', min: 40 },
    { day: 5, type: 'rest', min: 0 },
    { day: 6, type: 'tempo', min: 40 },
    { day: 7, type: 'long', min: 0 },
  ],
  peak: [
    { day: 1, type: 'rest', min: 0 },
    { day: 2, type: 'interval', min: 50 },
    { day: 3, type: 'easy', min: 40 },
    { day: 4, type: 'tempo', min: 40 },
    { day: 5, type: 'rest', min: 0 },
    { day: 6, type: 'easy', min: 40 },
    { day: 7, type: 'long', min: 0 },
  ],
  taper: [
    { day: 1, type: 'rest', min: 0 },
    { day: 2, type: 'threshold', min: 30 },
    { day: 3, type: 'easy', min: 30 },
    { day: 4, type: 'rest', min: 0 },
    { day: 5, type: 'easy', min: 25 },
    { day: 6, type: 'rest', min: 0 },
    { day: 7, type: 'easy', min: 40 },
  ],
  base: [
    { day: 1, type: 'rest', min: 0 },
    { day: 2, type: 'easy', min: 45 },
    { day: 3, type: 'easy', min: 50 },
    { day: 4, type: 'easy', min: 40 },
    { day: 5, type: 'rest', min: 0 },
    { day: 6, type: 'easy', min: 40 },
    { day: 7, type: 'long', min: 0 },
  ],
};

/** Total template minutes of a week's non-long running sessions. */
function templateNonLongMin(key: TemplateKey): number {
  return TEMPLATES[key].reduce((sum, s) => (s.type === 'long' ? sum : sum + s.min), 0);
}

/**
 * A week's sessions from its template: the long run lasts `longMin`, every other
 * running session `min * scale` (scale is 1 for an unseeded plan).
 */
function weeklySessions(
  key: TemplateKey,
  longMin: number,
  threshold: number,
  scale: number,
): PlanDay[] {
  return TEMPLATES[key].map((slot) => {
    const min = slot.type === 'long' ? longMin : slot.type === 'rest' ? 0 : slot.min * scale;
    return { day: slot.day, sessions: [makeSession(slot.type, min, threshold)] };
  });
}

/** Seeded plans never start below this many minutes per week. */
export const SEED_MIN_WEEKLY_MIN = 60;
/** Seeded long runs never start below this (unless the long-run share caps them). */
export const SEED_LONG_FLOOR_MIN = 40;
/** A seeded long run is at most this share of its week (the guardrail allows 45%). */
export const SEED_LONG_SHARE = 0.4;
/** Growth of volume and long run from one loading week to the next (at most +10%). */
export const SEED_WEEKLY_GROWTH = 1.08;
/** Seeded volume grows to at most this multiple of the starting volume... */
export const SEED_MAX_GROWTH = 2;
/** ...and never past this many minutes per week (unless the athlete already runs more). */
export const SEED_MAX_WEEKLY_MIN = 600;
/** A recovery week's volume as a share of the last loading week. */
export const SEED_RECOVERY_FACTOR = 0.65;
/** A taper week's volume as a share of the last loading week. */
export const SEED_TAPER_FACTOR = 0.5;
/** The long-run caps every plan shares: overall, and in a peak week. */
const LONG_CAP_MIN = 140;
const PEAK_LONG_CAP_MIN = 110;

/** A week's long run (minutes) and the scale applied to its other sessions. */
interface WeekShape {
  longMin: number;
  scale: number;
}

/**
 * Week shapes for a seeded plan (MVP-025). Week 1 totals the athlete's recent
 * weekly minutes (at least {@link SEED_MIN_WEEKLY_MIN}); its long run is their
 * recent longest run, at least {@link SEED_LONG_FLOOR_MIN} and at most
 * {@link SEED_LONG_SHARE} of the week and 140 min. A later loading week grows
 * volume by {@link SEED_WEEKLY_GROWTH} over the last loading week when it stays
 * in the same phase and holds it when the phase changes (intensity rises
 * instead), up to max(start, min(start * 2, 600)); the long run grows by the
 * same factor within the share and 140-min caps (110 in a peak week). A recovery
 * week is 0.65 and a taper week 0.5 of the last loading week's volume. The
 * weekly templates are the unseeded ones, scaled.
 */
function seededShapes(seed: FitnessSeed, weeks: number): WeekShape[] {
  const start = Math.max(SEED_MIN_WEEKLY_MIN, seed.weeklyMinutes);
  const cap = Math.max(start, Math.min(start * SEED_MAX_GROWTH, SEED_MAX_WEEKLY_MIN));
  const shapes: WeekShape[] = [];
  let loadVolume = start;
  let loadLong = 0;
  let loadPhase: PlanPhase | undefined;
  for (let w = 1; w <= weeks; w++) {
    const phase = phaseForWeek(w, weeks);
    const recovery = w % 4 === 0 && phase !== 'taper';
    const key: TemplateKey = recovery ? 'recovery' : phase;
    let volume: number;
    let longMin: number;
    if (phase === 'taper') {
      volume = loadVolume * SEED_TAPER_FACTOR;
      longMin = 0;
    } else if (recovery) {
      volume = loadVolume * SEED_RECOVERY_FACTOR;
      longMin = Math.min(loadLong, SEED_LONG_SHARE * volume);
    } else {
      if (loadPhase === undefined) {
        volume = start;
        longMin = Math.min(
          Math.max(seed.longestRunMin, SEED_LONG_FLOOR_MIN),
          LONG_CAP_MIN,
          SEED_LONG_SHARE * volume,
        );
      } else {
        const growth = phase === loadPhase ? SEED_WEEKLY_GROWTH : 1;
        volume = Math.min(cap, loadVolume * growth);
        longMin = Math.min(loadLong * SEED_WEEKLY_GROWTH, LONG_CAP_MIN, SEED_LONG_SHARE * volume);
      }
      if (phase === 'peak') longMin = Math.min(longMin, PEAK_LONG_CAP_MIN);
      loadVolume = volume;
      loadLong = longMin;
      loadPhase = phase;
    }
    shapes.push({ longMin, scale: (volume - longMin) / templateNonLongMin(key) });
  }
  return shapes;
}

/** Where a plan sits on the calendar once aligned to the goal race. */
export interface RaceAlignment {
  /** Plan start (YYYY-MM-DD): day 1 of week 1. */
  startDate: string;
  /** Number of plan weeks. */
  weeks: number;
  /** Race day (YYYY-MM-DD) = the last day of the last week, when aligned. */
  raceDate?: string;
}

/**
 * Align a plan to the goal race so its LAST week ends on race day. Without a
 * goal date (or one before the start) the plan keeps the requested start and
 * length. Otherwise it keeps the requested length when the race is far enough
 * away (starting later if needed), or shrinks to the whole weeks that fit
 * before the race (at least one), and the start is derived backwards from the
 * race date.
 */
export function alignToRace(startDate: string, weeks: number, goal: RaceGoal): RaceAlignment {
  const start = toDateKey(startDate);
  if (!goal.date) return { startDate: start, weeks };
  const race = toDateKey(goal.date);
  const span = daysBetween(start, race) + 1; // days available, race day included
  if (!Number.isFinite(span) || span < 1) return { startDate: start, weeks };
  const fit = Math.max(1, Math.min(weeks, Math.floor(span / 7)));
  return { startDate: addDays(race, -(fit * 7 - 1)), weeks: fit, raceDate: race };
}

/** The race itself: run at threshold-ish effort over the goal distance. */
export function raceSession(
  goal: RaceGoal,
  thresholdSpeedMps: number,
  date: string,
): WorkoutSuggestion {
  const speed = thresholdSpeedMps > 0 ? thresholdSpeedMps : DEFAULT_THRESHOLD_MPS;
  const minutes = resolveRaceDistanceM(goal) / speed / 60;
  return makeSession('race', minutes, speed, {
    date,
    description: `Race day: ${goal.name ?? goal.distance}. Warm up well, start controlled, and run your plan.`,
    rationale: 'Race day: the taper has you fresh, so trust your training.',
  });
}

/**
 * Put the race on day 7 of the race week. A hard day 6 is eased to an easy run
 * of the same length so the race never follows a quality session.
 */
function withRaceDay<D extends PlanDay>(
  days: D[],
  race: WorkoutSuggestion,
  threshold: number,
): D[] {
  return days.map((d): D => {
    if (d.day === 7) return { ...d, sessions: [{ ...race, date: d.date ?? race.date }] };
    if (d.day === 6 && d.sessions.some((s) => QUALITY_TYPES.has(s.type))) {
      const min = (d.sessions[0].targetDurationSec ?? 0) / 60;
      return { ...d, sessions: [makeSession('easy', min, threshold, { date: d.date })] };
    }
    return d;
  });
}

export interface PlanSkeletonParams {
  profile: AthleteProfile;
  goal: RaceGoal;
  weeks: number;
  startDate: string;
  planId: string;
  createdAt: string;
  /**
   * Recent training (MVP-025, see `fitnessSeed`). When present the plan starts
   * from it (volume, long run and, with a VDOT, paces); when absent the plan is
   * the fixed-minute skeleton.
   */
  seed?: FitnessSeed | null;
}

/** Threshold speed for a plan: from the seed's recent VDOT when known, else the profile's. */
function planThreshold(profile: AthleteProfile, seed: FitnessSeed | null | undefined): number {
  return seed?.vdot ? thresholdSpeedFromVdot(seed.vdot) : thresholdOf(profile);
}

/**
 * Build a periodized plan deterministically. Because the skeleton encodes the
 * progression rules, it satisfies the guardrails by construction; the validator
 * then double-checks (and the LLM only enriches language, never numbers).
 */
export function buildPlanSkeleton(params: PlanSkeletonParams): TrainingPlan {
  const { profile, goal, planId, createdAt, seed } = params;
  const threshold = planThreshold(profile, seed);
  // With a goal date the last week ends on race day (start/length derived from it).
  const { startDate: start, weeks, raceDate } = alignToRace(params.startDate, params.weeks, goal);
  const planWeeks: PlanWeek[] = [];
  const shapes = seed ? seededShapes(seed, weeks) : undefined;

  let longMin = 60;
  for (let w = 1; w <= weeks; w++) {
    const phase = phaseForWeek(w, weeks);
    const recovery = w % 4 === 0 && phase !== 'taper';
    const key: TemplateKey = recovery ? 'recovery' : phase;
    if (phase === 'taper') longMin = Math.max(40, longMin * 0.6);
    else if (!recovery) longMin = Math.min(LONG_CAP_MIN, longMin + 8);
    const unseeded: WeekShape = {
      longMin: recovery
        ? Math.min(longMin, 60)
        : phase === 'peak'
          ? Math.min(longMin, PEAK_LONG_CAP_MIN)
          : longMin,
      scale: 1,
    };
    const shape = shapes?.[w - 1] ?? unseeded;

    let days = weeklySessions(key, shape.longMin, threshold, shape.scale).map((d) => ({
      ...d,
      date: addDays(start, (w - 1) * 7 + (d.day - 1)),
      sessions: d.sessions.map((s) => ({ ...s, date: addDays(start, (w - 1) * 7 + (d.day - 1)) })),
    }));
    if (raceDate && w === weeks) {
      days = withRaceDay(days, raceSession(goal, threshold, raceDate), threshold);
    }

    const weekTss = days.reduce(
      (sum, d) => sum + d.sessions.reduce((s, x) => s + (x.targetTss ?? 0), 0),
      0,
    );
    const weekDistanceKm =
      days.reduce(
        (sum, d) => sum + d.sessions.reduce((s, x) => s + (x.targetDistanceM ?? 0), 0),
        0,
      ) / 1000;

    planWeeks.push({
      weekNumber: w,
      phase: recovery ? 'recovery' : phase,
      focus: recovery ? FOCUS.recovery : FOCUS[phase],
      targetTss: Number(weekTss.toFixed(0)),
      targetDistanceKm: Number(weekDistanceKm.toFixed(1)),
      days,
    });
  }

  return {
    id: planId,
    createdAt,
    goal,
    startDate: start,
    endDate: addDays(start, weeks * 7 - 1),
    summary: `A ${weeks}-week ${goal.name ?? goal.distance} plan: base → build → peak → taper, with a recovery week every 4th week.`,
    weeks: planWeeks,
  };
}

/** Deterministic per-type session durations (minutes). The LLM never sets these. */
const BASE_DURATION_MIN: Record<WorkoutType, number> = {
  easy: 45,
  long: 80,
  recovery: 30,
  tempo: 40,
  threshold: 50,
  interval: 50,
  repetition: 30,
  race: 30,
  rest: 0,
  cross_training: 40,
};

function durationForType(type: WorkoutType, phase: PlanPhase): number {
  if (type === 'long') return phase === 'taper' ? 50 : phase === 'base' ? 70 : 90;
  return BASE_DURATION_MIN[type];
}

/**
 * Materialize an LLM STRUCTURAL proposal (Option A) into a real plan. The model
 * supplies only structure (phase per week; per day a workout type + rationale);
 * every number — duration, pace, HR zone, load, distance — is computed here via
 * `makeSession` from the athlete's anchors, so compute-in-code stays inviolable.
 * The result is handed to the guardrail (validate → repair → re-validate); an
 * unusable/empty proposal returns an empty-week plan the caller rejects.
 *
 * Duplicate keys are DEDUPED, first occurrence wins: a repeated `weekNumber`
 * keeps the first week, a repeated `dayOfWeek` within a week keeps the first
 * day. With a goal date the plan is aligned so the last week ends on race day,
 * which gets a race session (see {@link alignToRace}).
 */
export function materializeProposal(
  proposal: LlmPlanProposal,
  params: PlanSkeletonParams,
): TrainingPlan {
  const { profile, goal, planId, createdAt, seed } = params;
  const threshold = planThreshold(profile, seed);
  const { startDate: start, weeks, raceDate } = alignToRace(params.startDate, params.weeks, goal);

  const planWeeks: PlanWeek[] = [];
  const seenWeeks = new Set<number>();
  // Stable sort: among duplicate week numbers the first proposed stays first.
  const sortedWeeks = [...proposal.weeks].sort((a, b) => a.weekNumber - b.weekNumber);
  for (const w of sortedWeeks) {
    if (w.weekNumber < 1 || w.weekNumber > weeks) continue; // clamp to the requested range
    if (seenWeeks.has(w.weekNumber)) continue; // duplicate week: keep the first
    seenWeeks.add(w.weekNumber);
    const phase = w.phase;
    const seenDays = new Set<number>();
    let days: PlanDay[] = [...w.days]
      .filter((d) => d.dayOfWeek >= 1 && d.dayOfWeek <= 7)
      .filter((d) => {
        if (seenDays.has(d.dayOfWeek)) return false; // duplicate day: keep the first
        seenDays.add(d.dayOfWeek);
        return true;
      })
      .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
      .map((d) => {
        const date = addDays(start, (w.weekNumber - 1) * 7 + (d.dayOfWeek - 1));
        const session = makeSession(
          d.workoutType,
          durationForType(d.workoutType, phase),
          threshold,
          { date, rationale: d.rationale },
        );
        return { day: d.dayOfWeek, date, sessions: [session] };
      });
    if (raceDate && w.weekNumber === weeks) {
      const raceDay: PlanDay = { day: 7, date: raceDate, sessions: [] };
      const withDay7 = days.some((d) => d.day === 7) ? days : [...days, raceDay];
      days = withRaceDay(withDay7, raceSession(goal, threshold, raceDate), threshold);
    }

    const weekTss = days.reduce(
      (sum, d) => sum + d.sessions.reduce((s, x) => s + (x.targetTss ?? 0), 0),
      0,
    );
    const weekDistanceKm =
      days.reduce(
        (sum, d) => sum + d.sessions.reduce((s, x) => s + (x.targetDistanceM ?? 0), 0),
        0,
      ) / 1000;

    planWeeks.push({
      weekNumber: w.weekNumber,
      phase,
      focus: FOCUS[phase],
      targetTss: Number(weekTss.toFixed(0)),
      targetDistanceKm: Number(weekDistanceKm.toFixed(1)),
      days,
    });
  }

  return {
    id: planId,
    createdAt,
    goal,
    startDate: start,
    endDate: addDays(start, weeks * 7 - 1),
    summary: `A ${weeks}-week ${goal.name ?? goal.distance} plan proposed by the coach and materialized from your anchors.`,
    weeks: planWeeks,
  };
}

export { QUALITY_TYPES };

function round(n: number): number {
  return Number(n.toFixed(0));
}
