import { z } from 'zod';
import { Activity } from './activity';
import { AthleteProfile, RaceGoal } from './athlete';
import { IntensityLabel, LoadMethod, PlanPhase, SportType, WorkoutType } from './enums';
import { AcwrPoint, PmcPoint, ZoneDistribution } from './metrics';

/**
 * The pre-computed "facts" bundle handed to Claude. Every number here is
 * computed deterministically in core; the LLM only reasons over it.
 */
export const CoachContext = z.object({
  generatedAt: z.string(),
  profile: AthleteProfile,
  /** Latest fitness/fatigue/form. */
  fitness: PmcPoint.optional(),
  acwr: AcwrPoint.optional(),
  /** CTL change per week (ramp rate). */
  rampRatePerWeek: z.number().optional(),
  /** Rolling 7-day intensity distribution. */
  weeklyDistribution: ZoneDistribution.optional(),
  weeklyVolumeKm: z.number().optional(),
  /** Most recent activities as compact summaries. */
  recentActivities: z.array(
    z.object({
      date: z.string(),
      name: z.string(),
      sportType: SportType,
      distanceKm: z.number(),
      durationSec: z.number(),
      tss: z.number(),
      loadMethod: LoadMethod,
      avgHr: z.number().optional(),
      avgPaceSecPerKm: z.number().optional(),
    }),
  ),
  goal: RaceGoal.optional(),
  daysToRace: z.number().optional(),
  planPhase: PlanPhase.optional(),
  notes: z.string().optional(),
});
export type CoachContext = z.infer<typeof CoachContext>;

/** A single prescribed workout. Also used as a plan "session." */
export const WorkoutSuggestion = z.object({
  type: WorkoutType,
  label: IntensityLabel.optional(),
  title: z.string(),
  description: z.string(),
  /** ISO date, when scheduled. */
  date: z.string().optional(),
  targetDistanceM: z.number().optional(),
  targetDurationSec: z.number().optional(),
  targetPaceSecPerKm: z.number().optional(),
  targetHrZone: z.number().int().optional(),
  targetTss: z.number().optional(),
  rationale: z.string(),
});
export type WorkoutSuggestion = z.infer<typeof WorkoutSuggestion>;

export const PlanDay = z.object({
  /** 1 = Monday … 7 = Sunday. */
  day: z.number().int().min(1).max(7),
  date: z.string().optional(),
  sessions: z.array(WorkoutSuggestion),
});
export type PlanDay = z.infer<typeof PlanDay>;

export const PlanWeek = z.object({
  weekNumber: z.number().int().positive(),
  phase: PlanPhase,
  focus: z.string(),
  targetTss: z.number().optional(),
  targetDistanceKm: z.number().optional(),
  days: z.array(PlanDay),
});
export type PlanWeek = z.infer<typeof PlanWeek>;

export const TrainingPlan = z.object({
  id: z.string(),
  createdAt: z.string(),
  goal: RaceGoal,
  startDate: z.string(),
  endDate: z.string().optional(),
  summary: z.string().optional(),
  weeks: z.array(PlanWeek),
});
export type TrainingPlan = z.infer<typeof TrainingPlan>;

/** Output of analyzing one workout. */
export const AnalysisResult = z.object({
  activity: Activity.omit({ streams: true }),
  headline: z.string(),
  explanation: z.string(),
  flags: z.array(z.string()).default([]),
});
export type AnalysisResult = z.infer<typeof AnalysisResult>;

export const RedFlagSeverity = z.enum(['info', 'warning', 'stop']);
export type RedFlagSeverity = z.infer<typeof RedFlagSeverity>;

/** A safety signal detected deterministically before/around LLM calls. */
export const RedFlag = z.object({
  severity: RedFlagSeverity,
  message: z.string(),
  source: z.string(),
});
export type RedFlag = z.infer<typeof RedFlag>;

/** A plan guardrail violation found by the deterministic validator. */
export const GuardrailViolation = z.object({
  rule: z.string(),
  message: z.string(),
  severity: z.enum(['warning', 'error']),
  weekNumber: z.number().int().optional(),
});
export type GuardrailViolation = z.infer<typeof GuardrailViolation>;

export const PlanValidation = z.object({
  valid: z.boolean(),
  violations: z.array(GuardrailViolation).default([]),
  /** True when the validator had to repair the proposed plan to make it valid. */
  repaired: z.boolean().default(false),
});
export type PlanValidation = z.infer<typeof PlanValidation>;
