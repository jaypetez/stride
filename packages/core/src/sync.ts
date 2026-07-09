import { AthleteProfile } from '@stride/schemas';
import { assertStravaConfigured, type StrideConfig } from './config';
import { type EstimatedAnchors, estimateAnchors } from './science/index';
import type { LocalStore } from './store/index';
import { StravaClient, StravaRateLimitError } from './strava/index';
import type { RateLimitStatus } from './strava/types';

const RUN_TYPES = new Set(['run', 'trail_run', 'treadmill_run']);

export interface SyncResult {
  fetched: number;
  enriched: number;
  total: number;
  pruned: number;
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
}

/**
 * Import activities from Strava into the local store: fetch summaries, enrich
 * recent runs with streams, merge (preserving prior streams), enforce the 7-day
 * cache limit, and fill in missing anchors. Shared by the CLI, API, and MCP.
 */
export async function syncStrava(params: SyncParams): Promise<SyncResult> {
  const { store, config } = params;
  assertStravaConfigured(config);
  const tokens = await store.loadTokens();
  if (!tokens) throw new Error('Not connected to Strava. Run `stride connect` first.');

  const client = new StravaClient({
    config: config.strava,
    tokens,
    onTokensRefreshed: (t) => store.saveTokens(t),
  });

  const fetchedAt = params.fetchedAt ?? new Date().toISOString();
  const summaries = await client.listAllActivities({ maxPages: params.pages ?? 3, fetchedAt });

  const enrichCount = params.enrichCount ?? 15;
  const toEnrich = summaries.filter((a) => RUN_TYPES.has(a.sportType)).slice(0, enrichCount);
  let enriched = 0;
  for (const activity of toEnrich) {
    try {
      activity.streams = await client.getActivityStreams(activity.id);
      enriched++;
    } catch (err) {
      if (err instanceof StravaRateLimitError) {
        params.onRateLimit?.();
        break;
      }
    }
  }

  const existing = await store.loadActivities();
  const byId = new Map(existing.map((a) => [a.id, a]));
  for (const activity of summaries) {
    const prev = byId.get(activity.id);
    byId.set(activity.id, { ...activity, streams: activity.streams ?? prev?.streams });
  }
  const merged = [...byId.values()];
  await store.saveActivities(merged);
  const pruned = await store.pruneExpiredStrava(params.nowMs ?? Date.now());

  let anchors: EstimatedAnchors | undefined;
  const profile = (await store.loadProfile()) ?? AthleteProfile.parse({});
  if (!profile.thresholdSpeedMps || !profile.lthr) {
    anchors = estimateAnchors(merged);
    const nowIso = new Date().toISOString();
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

  return {
    fetched: summaries.length,
    enriched,
    total: merged.length,
    pruned,
    anchors,
    rateLimit: client.getRateLimitStatus(),
  };
}
