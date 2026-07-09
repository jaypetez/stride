import { z } from 'zod';

/** Data provenance for an activity. */
export const ActivitySource = z.enum(['strava', 'upload', 'manual']);
export type ActivitySource = z.infer<typeof ActivitySource>;

/** Sport type. Stride is running-focused for the MVP but the model allows more. */
export const SportType = z.enum(['run', 'trail_run', 'treadmill_run', 'walk', 'hike', 'other']);
export type SportType = z.infer<typeof SportType>;

export const Sex = z.enum(['male', 'female', 'unspecified']);
export type Sex = z.infer<typeof Sex>;

export const ExperienceLevel = z.enum(['beginner', 'intermediate', 'advanced']);
export type ExperienceLevel = z.infer<typeof ExperienceLevel>;

export const Units = z.enum(['metric', 'imperial']);
export type Units = z.infer<typeof Units>;

/**
 * How a per-activity training load (TSS) was derived. The coach uses the most
 * accurate method available per activity (see the fallback chain in core).
 */
export const LoadMethod = z.enum([
  'rtss', // pace-based (Normalized Graded Pace vs threshold) — preferred
  'hrtss', // heart-rate-zone based
  'trimp', // Banister TRIMP (HR reserve)
  'duration', // duration × assumed intensity — last resort
  'none', // insufficient data
]);
export type LoadMethod = z.infer<typeof LoadMethod>;

/** Daniels-style intensity label attached to workouts and pace zones. */
export const IntensityLabel = z.enum(['E', 'M', 'T', 'I', 'R']);
export type IntensityLabel = z.infer<typeof IntensityLabel>;

export const WorkoutType = z.enum([
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
]);
export type WorkoutType = z.infer<typeof WorkoutType>;

/** Mesocycle phase in a periodized plan. */
export const PlanPhase = z.enum(['base', 'build', 'peak', 'taper', 'recovery']);
export type PlanPhase = z.infer<typeof PlanPhase>;

/** Common race distances (meters resolved in core). */
export const RaceDistance = z.enum(['5k', '10k', 'half', 'marathon', 'custom']);
export type RaceDistance = z.infer<typeof RaceDistance>;
