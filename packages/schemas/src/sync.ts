import { z } from 'zod';

/** Current on-disk shape version for the sync state (`meta.json`). */
export const SYNC_SCHEMA_VERSION = 1;

/**
 * Durable, non-Strava-content sync bookkeeping (`meta.json`). Records the
 * incremental watermark, backfill progress, and reconciliation timestamps so a
 * sync can resume and stay incremental across runs.
 *
 * None of these fields reproduce Strava activity content — they are process
 * metadata — so they persist durably alongside the derived daily-load series.
 */
export const SyncState = z.object({
  /** On-disk schema version; lets future migrations detect old state. */
  schemaVersion: z.number().int().default(SYNC_SCHEMA_VERSION),
  /** ISO timestamp of the last successful sync (the incremental watermark). */
  lastSyncedAt: z.string().optional(),
  /** Whether the full history backfill has completed (else keep backfilling). */
  backfillComplete: z.boolean().default(false),
  /**
   * Resume point for a backfill truncated by a rate limit: the epoch-seconds
   * `before` bound to page from on the next run (oldest activity fetched).
   */
  backfillCursor: z.string().optional(),
  /** The connected athlete's Strava id (for deletion certification / audits). */
  athleteId: z.number().optional(),
  /** ISO timestamp of the last deletion reconciliation pass. */
  lastReconcileAt: z.string().optional(),
});
export type SyncState = z.infer<typeof SyncState>;
