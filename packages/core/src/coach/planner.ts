import type {
  AthleteProfile,
  CoachContext,
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
  const distanceM = Math.round(speed * durationSec);
  return {
    type,
    label: it.label,
    title: `${TITLES[type]} (${Math.round(durationMin)} min)`,
    description: opts.description ?? describeType(type, durationMin),
    date: opts.date,
    targetDistanceM: distanceM,
    targetDurationSec: durationSec,
    targetPaceSecPerKm: Math.round(mpsToSecPerKm(speed)),
    targetHrZone: it.hrZone,
    targetTss: tss,
    rationale: opts.rationale ?? defaultRationale(type),
  };
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
  const it = INTENSITY[session.type];
  const threshold =
    session.targetPaceSecPerKm && it.paceIf > 0
      ? 1000 / session.targetPaceSecPerKm / it.paceIf
      : DEFAULT_THRESHOLD_MPS;
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

function weeklySessions(
  phase: PlanPhase,
  recovery: boolean,
  longMin: number,
  threshold: number,
): PlanDay[] {
  const day = (d: number, s: WorkoutSuggestion): PlanDay => ({ day: d, sessions: [s] });
  const rest = (d: number) => day(d, makeSession('rest', 0, threshold));

  if (recovery) {
    return [
      rest(1),
      day(2, makeSession('easy', 35, threshold)),
      day(3, makeSession('easy', 40, threshold)),
      rest(4),
      day(5, makeSession('easy', 30, threshold)),
      rest(6),
      day(7, makeSession('long', Math.min(longMin, 60), threshold)),
    ];
  }

  switch (phase) {
    case 'build':
      return [
        rest(1),
        day(2, makeSession('threshold', 50, threshold)),
        day(3, makeSession('easy', 45, threshold)),
        day(4, makeSession('easy', 40, threshold)),
        rest(5),
        day(6, makeSession('tempo', 40, threshold)),
        day(7, makeSession('long', longMin, threshold)),
      ];
    case 'peak':
      return [
        rest(1),
        day(2, makeSession('interval', 50, threshold)),
        day(3, makeSession('easy', 40, threshold)),
        day(4, makeSession('tempo', 40, threshold)),
        rest(5),
        day(6, makeSession('easy', 40, threshold)),
        day(7, makeSession('long', Math.min(longMin, 110), threshold)),
      ];
    case 'taper':
      return [
        rest(1),
        day(2, makeSession('threshold', 30, threshold)),
        day(3, makeSession('easy', 30, threshold)),
        rest(4),
        day(5, makeSession('easy', 25, threshold)),
        rest(6),
        day(7, makeSession('easy', 40, threshold)),
      ];
    default: // base
      return [
        rest(1),
        day(2, makeSession('easy', 45, threshold)),
        day(3, makeSession('easy', 50, threshold)),
        day(4, makeSession('easy', 40, threshold)),
        rest(5),
        day(6, makeSession('easy', 40, threshold)),
        day(7, makeSession('long', longMin, threshold)),
      ];
  }
}

export interface PlanSkeletonParams {
  profile: AthleteProfile;
  goal: RaceGoal;
  weeks: number;
  startDate: string;
  planId: string;
  createdAt: string;
}

/**
 * Build a periodized plan deterministically. Because the skeleton encodes the
 * progression rules, it satisfies the guardrails by construction; the validator
 * then double-checks (and the LLM only enriches language, never numbers).
 */
export function buildPlanSkeleton(params: PlanSkeletonParams): TrainingPlan {
  const { profile, goal, weeks, planId, createdAt } = params;
  const threshold = thresholdOf(profile);
  const start = toDateKey(params.startDate);
  const planWeeks: PlanWeek[] = [];

  let longMin = 60;
  for (let w = 1; w <= weeks; w++) {
    const phase = phaseForWeek(w, weeks);
    const recovery = w % 4 === 0 && phase !== 'taper';
    if (phase === 'taper') longMin = Math.max(40, longMin * 0.6);
    else if (!recovery) longMin = Math.min(140, longMin + 8);

    const days = weeklySessions(phase, recovery, longMin, threshold).map((d) => ({
      ...d,
      date: addDays(start, (w - 1) * 7 + (d.day - 1)),
      sessions: d.sessions.map((s) => ({ ...s, date: addDays(start, (w - 1) * 7 + (d.day - 1)) })),
    }));

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
 */
export function materializeProposal(
  proposal: LlmPlanProposal,
  params: PlanSkeletonParams,
): TrainingPlan {
  const { profile, goal, weeks, planId, createdAt } = params;
  const threshold = thresholdOf(profile);
  const start = toDateKey(params.startDate);

  const planWeeks: PlanWeek[] = [];
  const sortedWeeks = [...proposal.weeks].sort((a, b) => a.weekNumber - b.weekNumber);
  for (const w of sortedWeeks) {
    if (w.weekNumber < 1 || w.weekNumber > weeks) continue; // clamp to the requested range
    const phase = w.phase;
    const days: PlanDay[] = [...w.days]
      .filter((d) => d.dayOfWeek >= 1 && d.dayOfWeek <= 7)
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
