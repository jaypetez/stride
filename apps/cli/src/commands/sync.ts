import { assertStravaConfigured, syncStrava } from '@stride/core';
import { loadApp } from '../app';
import { dim, errorMsg, info, success, warn } from '../ui';

export async function syncCommand(opts: {
  pages?: string;
  full?: boolean;
  rebuild?: boolean;
  backfill?: boolean;
  reconcile?: boolean;
}): Promise<void> {
  const app = loadApp();
  try {
    assertStravaConfigured(app.config);
  } catch (err) {
    errorMsg((err as Error).message);
    return;
  }

  let pages: number | undefined;
  if (opts.pages !== undefined) {
    const n = Number(opts.pages);
    if (!Number.isFinite(n) || n <= 0) {
      errorMsg(`Invalid --pages "${opts.pages}"; expected a positive number.`);
      return;
    }
    pages = Math.floor(n);
  }

  info('Fetching activities from Strava…');
  try {
    const result = await syncStrava({
      store: app.store,
      config: app.config,
      pages,
      enrichCount: opts.full ? 30 : 15,
      rebuild: opts.rebuild,
      backfill: opts.backfill,
      reconcile: opts.reconcile,
      onRateLimit: () => warn('Hit Strava rate limit; stopping early and saving partial progress.'),
    });

    if (result.anchors?.thresholdSpeedMps) {
      success(
        `Estimated anchors — threshold ${result.anchors.thresholdSpeedMps} m/s · VDOT ${result.anchors.vdot} · maxHR ${result.anchors.maxHr} · LTHR ${result.anchors.lthr}`,
      );
    }
    success(
      `Synced ${result.fetched} activities (${result.enriched} with streams) in ${result.mode} mode. ` +
        `${result.total} raw stored · ${result.daysUpdated} load-days updated.`,
    );
    if (result.pruned > 0)
      dim(`Pruned ${result.pruned} Strava activities past the 7-day cache limit.`);
    if (result.deleted > 0) dim(`Reconciled ${result.deleted} activities deleted on Strava.`);
    const rl = result.rateLimit;
    if (rl)
      dim(
        `Rate limit: ${rl.shortUsage}/${rl.shortLimit} (15 min) · ${rl.dailyUsage}/${rl.dailyLimit} (day).`,
      );
  } catch (err) {
    errorMsg(`Sync failed: ${(err as Error).message}`);
  }
}
