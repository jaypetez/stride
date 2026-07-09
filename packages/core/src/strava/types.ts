import { z } from 'zod';

/** OAuth tokens for a connected Strava athlete. Persisted locally (0600). */
export const StravaTokens = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  /** Epoch seconds at which the access token expires. */
  expiresAt: z.number(),
  scope: z.string().optional(),
  athleteId: z.number().optional(),
});
export type StravaTokens = z.infer<typeof StravaTokens>;

/** Snapshot of Strava's rate-limit headers (15-min and daily). */
export interface RateLimitStatus {
  shortLimit: number;
  shortUsage: number;
  dailyLimit: number;
  dailyUsage: number;
  readShortLimit?: number;
  readShortUsage?: number;
  readDailyLimit?: number;
  readDailyUsage?: number;
}
