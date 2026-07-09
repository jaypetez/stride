import { z } from 'zod';
import { IntensityLabel, LoadMethod } from './enums';

/** A heart-rate training zone (bpm bounds). */
export const HrZone = z.object({
  zone: z.number().int(),
  name: z.string(),
  minBpm: z.number(),
  maxBpm: z.number(),
});
export type HrZone = z.infer<typeof HrZone>;

/** A pace training zone expressed in speed (m/s) so bounds are monotonic. */
export const PaceZone = z.object({
  zone: z.number().int(),
  label: IntensityLabel,
  name: z.string(),
  minSpeedMps: z.number(),
  maxSpeedMps: z.number(),
});
export type PaceZone = z.infer<typeof PaceZone>;

export const Zones = z.object({
  hr: z.array(HrZone).default([]),
  pace: z.array(PaceZone).default([]),
});
export type Zones = z.infer<typeof Zones>;

/** Three-zone (polarized) time distribution used to steer toward ~80/20. */
export const ZoneDistribution = z.object({
  easySec: z.number().nonnegative(),
  moderateSec: z.number().nonnegative(),
  hardSec: z.number().nonnegative(),
  easyPct: z.number(),
  moderatePct: z.number(),
  hardPct: z.number(),
});
export type ZoneDistribution = z.infer<typeof ZoneDistribution>;

/** Computed metrics for a single activity. All numbers come from code, not the LLM. */
export const ActivityMetrics = z.object({
  activityId: z.string(),
  tss: z.number().nonnegative(),
  method: LoadMethod,
  intensityFactor: z.number().optional(),
  durationSec: z.number().nonnegative(),
  distanceM: z.number().nonnegative(),
  averageSpeedMps: z.number().optional(),
  /** Normalized Graded Pace expressed as speed (m/s). */
  gradeAdjustedSpeedMps: z.number().optional(),
  averagePaceSecPerKm: z.number().optional(),
  gradeAdjustedPaceSecPerKm: z.number().optional(),
  averageHr: z.number().optional(),
  /** NGP-or-speed / avg HR — aerobic efficiency. */
  efficiencyFactor: z.number().optional(),
  /** Pa:HR decoupling percent over a steady run. */
  aerobicDecouplingPct: z.number().optional(),
  /** Seconds spent in each HR zone, keyed by zone number. */
  hrZoneSeconds: z.record(z.string(), z.number()).optional(),
  /** Seconds spent in each pace zone, keyed by zone number. */
  paceZoneSeconds: z.record(z.string(), z.number()).optional(),
  zoneDistribution: ZoneDistribution.optional(),
});
export type ActivityMetrics = z.infer<typeof ActivityMetrics>;

/** One day of aggregated training load — the single source of truth downstream. */
export const DailyLoad = z.object({
  /** YYYY-MM-DD (local). */
  date: z.string(),
  tss: z.number().nonnegative(),
  durationSec: z.number().nonnegative(),
  distanceM: z.number().nonnegative(),
  method: LoadMethod,
  activityIds: z.array(z.string()).default([]),
});
export type DailyLoad = z.infer<typeof DailyLoad>;

/** A point on the Performance Management Chart. */
export const PmcPoint = z.object({
  date: z.string(),
  /** Chronic Training Load (fitness), 42-day EWMA. */
  ctl: z.number(),
  /** Acute Training Load (fatigue), 7-day EWMA. */
  atl: z.number(),
  /** Training Stress Balance (form) = yesterday's CTL - ATL. */
  tsb: z.number(),
});
export type PmcPoint = z.infer<typeof PmcPoint>;

export const AcwrFlag = z.enum(['low', 'ok', 'high', 'very_high']);
export type AcwrFlag = z.infer<typeof AcwrFlag>;

/** Acute:chronic workload ratio (EWMA method) — an injury-risk guardrail. */
export const AcwrPoint = z.object({
  date: z.string(),
  acwr: z.number(),
  acuteLoad: z.number(),
  chronicLoad: z.number(),
  flag: AcwrFlag,
});
export type AcwrPoint = z.infer<typeof AcwrPoint>;
