import { assertStravaConfigured, syncStrava } from '@stride/core';
import { loadApp } from '../app';
import { dim, errorMsg, info, success, warn } from '../ui';

export async function syncCommand(opts: { pages?: string; full?: boolean }): Promise<void> {
  const app = loadApp();
  try {
    assertStravaConfigured(app.config);
  } catch (err) {
    errorMsg((err as Error).message);
    return;
  }

  info('Fetching activities from Strava…');
  try {
    const result = await syncStrava({
      store: app.store,
      config: app.config,
      pages: opts.pages ? Number(opts.pages) : undefined,
      enrichCount: opts.full ? 30 : 15,
      onRateLimit: () =>
        warn('Hit Strava rate limit while fetching streams; stopping enrichment early.'),
    });

    if (result.anchors?.thresholdSpeedMps) {
      success(
        `Estimated anchors — threshold ${result.anchors.thresholdSpeedMps} m/s · VDOT ${result.anchors.vdot} · maxHR ${result.anchors.maxHr} · LTHR ${result.anchors.lthr}`,
      );
    }
    success(
      `Synced ${result.fetched} activities (${result.enriched} with streams). ${result.total} total stored.`,
    );
    if (result.pruned > 0)
      dim(`Pruned ${result.pruned} Strava activities past the 7-day cache limit.`);
    const rl = result.rateLimit;
    if (rl)
      dim(
        `Rate limit: ${rl.shortUsage}/${rl.shortLimit} (15 min) · ${rl.dailyUsage}/${rl.dailyLimit} (day).`,
      );
  } catch (err) {
    errorMsg(`Sync failed: ${(err as Error).message}`);
  }
}
