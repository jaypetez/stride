import { z } from 'zod';
import { ActivitySource, SportType } from './enums';

/**
 * Index-aligned time-series streams for an activity. All present streams share
 * the same length and can be zipped point-by-point. Mirrors the Strava stream
 * set; every field is optional because devices/activities vary.
 */
export const ActivityStreams = z.object({
  /** Seconds from start. */
  time: z.array(z.number()).optional(),
  /** Cumulative distance in meters. */
  distance: z.array(z.number()).optional(),
  /** Altitude in meters. */
  altitude: z.array(z.number()).optional(),
  /** Smoothed instantaneous velocity in m/s. */
  velocitySmooth: z.array(z.number()).optional(),
  /** Heart rate in bpm. */
  heartrate: z.array(z.number()).optional(),
  /** Cadence in rpm (per leg for running). */
  cadence: z.array(z.number()).optional(),
  /** Power in watts (rare for running). */
  watts: z.array(z.number()).optional(),
  /** Grade in percent. */
  gradeSmooth: z.array(z.number()).optional(),
  /** Whether the athlete was moving at each sample. */
  moving: z.array(z.boolean()).optional(),
});
export type ActivityStreams = z.infer<typeof ActivityStreams>;

/**
 * A normalized activity. Stride maps Strava (and later FIT/GPX uploads) into
 * this shape so the rest of the system is source-agnostic.
 */
export const Activity = z.object({
  id: z.string(),
  source: ActivitySource,
  sportType: SportType,
  name: z.string(),
  /** ISO-8601 UTC start. */
  startDate: z.string(),
  /** ISO-8601 local start. */
  startDateLocal: z.string().optional(),
  timezone: z.string().optional(),
  /** Meters. */
  distance: z.number().nonnegative(),
  /** Seconds. */
  movingTime: z.number().nonnegative(),
  /** Seconds. */
  elapsedTime: z.number().nonnegative(),
  /** Meters. */
  totalElevationGain: z.number().nonnegative().default(0),
  elevHigh: z.number().optional(),
  elevLow: z.number().optional(),
  /** m/s. */
  averageSpeed: z.number().optional(),
  maxSpeed: z.number().optional(),
  averageHeartrate: z.number().optional(),
  maxHeartrate: z.number().optional(),
  hasHeartrate: z.boolean().default(false),
  averageCadence: z.number().optional(),
  /** Whether GPS/pace can be trusted (false for treadmill/manual). */
  trainer: z.boolean().default(false),
  manual: z.boolean().default(false),
  streams: ActivityStreams.optional(),
  /** When Stride fetched/cached this record (ISO). Used for 7-day expiry. */
  fetchedAt: z.string().optional(),
});
export type Activity = z.infer<typeof Activity>;

/** A compact activity view for lists and LLM context (no streams). */
export const ActivitySummary = Activity.omit({ streams: true });
export type ActivitySummary = z.infer<typeof ActivitySummary>;
