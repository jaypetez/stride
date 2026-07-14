import { type Activity, AthleteProfile, type DailyLoad, SyncState } from '@stride/schemas';
import { assertStravaConfigured, type StrideConfig } from './config';
import { toDateKey } from './science/dates';
import { type EstimatedAnchors, estimateAnchors } from './science/index';
import { toDailyLoads } from './science/pmc';
import type { LocalStore } from './store/index';
import type { FetchLike } from './strava/index';
import { type SleepFn, StravaClient, StravaRateLimitError } from './strava/index';
import type { RateLimitStatus, StravaTokens } from './strava/types';

const MS_PER_DAY = 86_400_000;
const RETENTION_DAYS = 7;
const RUN_TYPES = new Set(['run', 'trail_run', 'treadmill_run']);

/** Overlap the incremental `after` watermark by ~a day to catch late edits. */
const INCREMENTAL_OVERLAP_SEC = 24 * 60 * 60;
const DEFAULT_BACKFILL_PAGES = 20;
const DEFAULT_INCREMENTAL_PAGES = 2;
const DEFAULT_ENRICH_COUNT = 15;

export type SyncMode = 'incremental' | 'backfill' | 'rebuild';

export interface SyncResult {
  fetched: number;
  enriched: number;
  total: number;
  pruned: number;
  /** Which strategy ran this pass. */
  mode: SyncMode;
  /** Number of durable daily-load days recomputed and upserted this pass. */
  daysUpdated: number;
  /** Raw activities removed by deletion reconciliation. */
  deleted: number;
  anchors?: EstimatedAnchors;
  rateLimit?: RateLimitStatus;
}

export interface SyncParams {
  store: LocalStore;
  config: StrideConfig;
  pages?: number;
  enrichCount?: number;
  fetchedAt?: string;
  nowMs?: number;
  onRateLimit?: () => void;
  /** Force a full re-download and wholesale rebuild of the durable series. */
  rebuild?: boolean;
  /** Force a (further) history backfill regardless of the stored watermark. */
  backfill?: boolean;
  /** Opt into deletion reconciliation on an incremental sync. */
  reconcile?: boolean;
  /** Test seams for the Strava client (offline tests inject these). */
  fetchImpl?: FetchLike;
  sleep?: SleepFn;
  nowSec?: () => number;
}

function resolveMode(params: SyncParams, state: SyncState): SyncMode {
  if (params.rebuild) return 'rebuild';
  if (params.backfill) return 'backfill';
  if (!state.lastSyncedAt || !state.backfillComplete) return 'backfill';
  return 'incremental';
}

function oldestStartEpochSec(activities: Activity[]): number | undefined {
  let min: number | undefined;
  for (const a of activities) {
    const t = Date.parse(a.startDate);
    if (Number.isFinite(t) && (min === undefined || t < min)) min = t;
  }
  return min === undefined ? undefined : Math.floor(min / 1000);
}

/**
 * Import activities from Strava into the local store and refresh the durable
 * daily-load series that is the single source of truth for the PMC/ACWR
 * (GOAL.md §7). The flow is deliberately ordered so a day's *derived* aggregate
 * is persisted durably before its *raw* Strava data is pruned at the 7-day
 * cache limit (GOAL.md §4):
 *
 *   1. migration-seed the durable series from existing raw (upgrade safety);
 *   2. fetch (backfill newest→oldest, incremental via `after`, or rebuild);
 *   3. enrich recent runs with streams (run-only, budgeted);
 *   4. merge into retained raw (preserving prior streams) + prune raw > 7 days;
 *   5. reconcile deletions within the fetched window (backfill/rebuild/opt-in);
 *   6. recompute + upsert the durable daily-load series;
 *   7. persist the watermark / backfill state.
 *
 * The whole body runs under a cross-process advisory lock.
 */
export async function syncStrava(params: SyncParams): Promise<SyncResult> {
  const { store, config } = params;
  assertStravaConfigured(config);
  const tokens = await store.loadTokens();
  if (!tokens) throw new Error('Not connected to Strava. Run `stride connect` first.');

  const nowMs = params.nowMs ?? Date.now();
  const lock = await store.acquireSyncLock(nowMs);
  try {
    return await runSync(params, tokens, nowMs);
  } finally {
    await lock.release();
  }
}

async function runSync(
  params: SyncParams,
  tokens: StravaTokens,
  nowMs: number,
): Promise<SyncResult> {
  const { store, config } = params;

  const client = new StravaClient({
    config: config.strava,
    tokens,
    onTokensRefreshed: (t) => store.saveTokens(t),
    fetchImpl: params.fetchImpl,
    sleep: params.sleep,
    now: params.nowSec,
  });

  const fetchedAt = params.fetchedAt ?? new Date(nowMs).toISOString();
  const profile = (await store.loadProfile()) ?? AthleteProfile.parse({});
  const state = (await store.loadSyncState()) ?? SyncState.parse({});
  const mode = resolveMode(params, state);

  // (1) Migration seed: an existing user upgrading has raw activities but no
  // durable series yet. Derive it BEFORE any prune so the PMC never collapses
  // to zero on the first post-upgrade sync.
  const existingRaw = await store.loadActivities();
  if (mode !== 'rebuild') {
    const existingDaily = await store.loadDailyLoads();
    if (existingDaily.length === 0 && existingRaw.length > 0) {
      await store.saveDailyLoads(toDailyLoads(existingRaw, profile));
    }
  }

  // (2) Fetch.
  const fetch = await fetchActivities(client, params, state, mode, fetchedAt);

  // (3) Enrich recent runs with streams (run-only, budgeted). Degrade on limit.
  const enrichCount = params.enrichCount ?? DEFAULT_ENRICH_COUNT;
  const toEnrich = fetch.summaries.filter((a) => RUN_TYPES.has(a.sportType)).slice(0, enrichCount);
  let enriched = 0;
  let rateLimited = fetch.rateLimited;
  for (const activity of toEnrich) {
    try {
      activity.streams = await client.getActivityStreams(activity.id);
      enriched++;
    } catch (err) {
      if (err instanceof StravaRateLimitError) {
        rateLimited = true;
        params.onRateLimit?.();
        break;
      }
      throw err;
    }
  }
  if (fetch.rateLimited) params.onRateLimit?.();

  // (4) Merge new summaries into retained raw (preserving prior streams) and
  // enforce the 7-day raw cache limit.
  const startRaw = mode === 'rebuild' ? [] : existingRaw;
  const byId = new Map(startRaw.map((a) => [a.id, a]));
  for (const activity of fetch.summaries) {
    const prev = byId.get(activity.id);
    byId.set(activity.id, { ...activity, streams: activity.streams ?? prev?.streams });
  }
  const merged = [...byId.values()];
  await store.saveActivities(merged);
  const pruned = await store.pruneExpiredStrava(nowMs, RETENTION_DAYS);

  // (5) Deletion reconciliation: within the fetched date range, retained raw
  // Strava activities Strava no longer returns were deleted upstream — remove
  // them so the durable series can't count phantom load.
  let retained = await store.loadActivities();
  let deleted = 0;
  const doReconcile = mode === 'backfill' || mode === 'rebuild' || params.reconcile === true;
  if (doReconcile && fetch.summaries.length > 0) {
    const fetchedIds = new Set(fetch.summaries.map((a) => a.id));
    let minStart = fetch.summaries[0].startDate;
    let maxStart = fetch.summaries[0].startDate;
    for (const a of fetch.summaries) {
      if (a.startDate < minStart) minStart = a.startDate;
      if (a.startDate > maxStart) maxStart = a.startDate;
    }
    const kept = retained.filter((a) => {
      if (a.source !== 'strava') return true; // Only reconcile Strava-sourced data.
      if (a.startDate < minStart || a.startDate > maxStart) return true; // Outside window.
      return fetchedIds.has(a.id); // In window: keep only if Strava still has it.
    });
    deleted = retained.length - kept.length;
    if (deleted > 0) {
      await store.saveActivities(kept);
      retained = kept;
    }
  }

  // (6) Recompute + upsert the durable daily-load series. ORDERING IS
  // LOAD-BEARING: this runs every sync for every still-live day, so a day's
  // durable entry is current before its raw data expires.
  const recomputed = toDailyLoads(retained, profile);
  const retentionCutoff = toDateKey(new Date(nowMs - RETENTION_DAYS * MS_PER_DAY).toISOString());
  let durable: DailyLoad[];
  if (mode === 'rebuild') {
    // Rebuild replaces the durable series wholesale from the re-downloaded raw.
    durable = recomputed
      .map((d) =>
        d.date < retentionCutoff && d.activityIds.length > 0 ? { ...d, activityIds: [] } : d,
      )
      .sort((a, b) => a.date.localeCompare(b.date));
    await store.saveDailyLoads(durable);
  } else {
    durable = await store.upsertDailyLoads(recomputed, retentionCutoff);
  }

  // Fill in missing anchors from the freshly merged data (unchanged behavior).
  let anchors: EstimatedAnchors | undefined;
  if (!profile.thresholdSpeedMps || !profile.lthr) {
    anchors = estimateAnchors(merged);
    const nowIso = fetchedAt;
    await store.saveProfile(
      AthleteProfile.parse({
        ...profile,
        thresholdSpeedMps: profile.thresholdSpeedMps ?? anchors.thresholdSpeedMps,
        vdot: profile.vdot ?? anchors.vdot,
        maxHr: profile.maxHr ?? anchors.maxHr,
        lthr: profile.lthr ?? anchors.lthr,
        anchorsUpdatedAt: nowIso,
        updatedAt: nowIso,
      }),
    );
  }

  // (7) Persist the watermark / backfill state.
  const nextState = SyncState.parse({
    ...state,
    lastSyncedAt: fetchedAt,
    athleteId: tokens.athleteId ?? state.athleteId,
    lastReconcileAt: doReconcile ? fetchedAt : state.lastReconcileAt,
  });
  if (mode === 'incremental') {
    // Incremental doesn't change backfill progress.
  } else if (rateLimited && !fetch.reachedEnd) {
    // Backfill/rebuild truncated by a rate limit: stay incomplete and record a
    // resume cursor (oldest fetched start) so the next run continues older.
    nextState.backfillComplete = false;
    const cursor = oldestStartEpochSec(fetch.summaries);
    if (cursor !== undefined) nextState.backfillCursor = String(cursor);
  } else {
    // Backfill/rebuild finished (short page or page budget hit): mark complete.
    nextState.backfillComplete = true;
    nextState.backfillCursor = undefined;
  }
  await store.saveSyncState(nextState);

  return {
    fetched: fetch.summaries.length,
    enriched,
    total: retained.length,
    pruned,
    mode,
    daysUpdated: recomputed.length,
    deleted,
    anchors,
    rateLimit: client.getRateLimitStatus(),
  };
}

interface FetchOutcome {
  summaries: Activity[];
  rateLimited: boolean;
  reachedEnd: boolean;
}

async function fetchActivities(
  client: StravaClient,
  params: SyncParams,
  state: SyncState,
  mode: SyncMode,
  fetchedAt: string,
): Promise<FetchOutcome> {
  if (mode === 'incremental') {
    const watermarkSec = state.lastSyncedAt ? Math.floor(Date.parse(state.lastSyncedAt) / 1000) : 0;
    const after = Math.max(0, watermarkSec - INCREMENTAL_OVERLAP_SEC);
    const result = await client.listAllActivities({
      after: after > 0 ? after : undefined,
      maxPages: params.pages ?? DEFAULT_INCREMENTAL_PAGES,
      fetchedAt,
    });
    return {
      summaries: result.activities,
      rateLimited: result.rateLimited,
      reachedEnd: result.reachedEnd,
    };
  }

  // backfill or rebuild: page newest→oldest. Resume from a persisted cursor
  // (unless rebuilding, which always starts fresh).
  const before =
    mode === 'backfill' && state.backfillCursor ? Number(state.backfillCursor) : undefined;
  const result = await client.listAllActivities({
    before: Number.isFinite(before) ? before : undefined,
    maxPages: params.pages ?? DEFAULT_BACKFILL_PAGES,
    fetchedAt,
  });
  return {
    summaries: result.activities,
    rateLimited: result.rateLimited,
    reachedEnd: result.reachedEnd,
  };
}
