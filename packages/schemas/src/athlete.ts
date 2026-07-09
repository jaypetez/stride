import { z } from 'zod';
import { ExperienceLevel, RaceDistance, Sex, Units } from './enums';

/**
 * The persistent athlete model. This is passed to the coach on every request so
 * constraints are never "forgotten." Threshold anchors (threshold speed + LTHR)
 * drive every training-load computation and should be recomputed every 4-6 weeks.
 */
export const AthleteProfile = z.object({
  id: z.string().default('me'),
  name: z.string().optional(),
  sex: Sex.default('unspecified'),
  age: z.number().int().positive().optional(),
  weightKg: z.number().positive().optional(),
  units: Units.default('metric'),
  experienceLevel: ExperienceLevel.default('intermediate'),

  // --- Physiological anchors (the foundation of every metric) ---
  /** Functional threshold running speed in m/s (pace sustainable ~1 hour). */
  thresholdSpeedMps: z.number().positive().optional(),
  /** Lactate threshold heart rate in bpm. */
  lthr: z.number().positive().optional(),
  /** Maximum heart rate in bpm (measured or estimated 208 - 0.7*age). */
  maxHr: z.number().positive().optional(),
  /** Resting heart rate in bpm. */
  restingHr: z.number().positive().optional(),
  /** Jack Daniels VDOT (pseudo-VO2max). */
  vdot: z.number().positive().optional(),
  /** ISO date the anchors were last recalculated. */
  anchorsUpdatedAt: z.string().optional(),

  // --- Coaching context ---
  goals: z.string().optional(),
  injuryHistory: z.array(z.string()).default([]),
  /** PAR-Q-style screening: true once the athlete confirms readiness. */
  medicalClearance: z.boolean().default(false),
  /** Screening flags raised during onboarding (e.g. "chest pain"). */
  healthFlags: z.array(z.string()).default([]),

  updatedAt: z.string().optional(),
});
export type AthleteProfile = z.infer<typeof AthleteProfile>;

/** A goal race the plan is built toward. */
export const RaceGoal = z.object({
  distance: RaceDistance.default('10k'),
  /** Required when distance is "custom". */
  customDistanceM: z.number().positive().optional(),
  name: z.string().optional(),
  /** ISO date of the race. */
  date: z.string().optional(),
  /** Optional goal finish time in seconds. */
  goalTimeSec: z.number().positive().optional(),
});
export type RaceGoal = z.infer<typeof RaceGoal>;
