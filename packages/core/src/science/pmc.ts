import type { Activity, AthleteProfile, DailyLoad, LoadMethod, PmcPoint } from '@stride/schemas';
import { eachDay, toDateKey } from './dates';
import { computeActivityLoad } from './load';

const METHOD_RANK: Record<LoadMethod, number> = {
  rtss: 4,
  hrtss: 3,
  trimp: 2,
  duration: 1,
  none: 0,
};

/** Aggregate activities into a daily training-load series (sorted by date). */
export function toDailyLoads(activities: Activity[], profile: AthleteProfile): DailyLoad[] {
  const byDate = new Map<string, DailyLoad>();
  for (const activity of activities) {
    const load = computeActivityLoad(activity, profile);
    const date = toDateKey(activity.startDateLocal ?? activity.startDate);
    const existing = byDate.get(date);
    if (existing) {
      existing.tss += load.tss;
      existing.durationSec += load.durationSec;
      existing.distanceM += load.distanceM;
      existing.activityIds.push(activity.id);
      if (METHOD_RANK[load.method] > METHOD_RANK[existing.method]) {
        existing.method = load.method;
      }
    } else {
      byDate.set(date, {
        date,
        tss: load.tss,
        durationSec: load.durationSec,
        distanceM: load.distanceM,
        method: load.method,
        activityIds: [activity.id],
      });
    }
  }
  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));
}

export interface PmcOptions {
  ctlDays?: number;
  atlDays?: number;
  /** Seed values (e.g. carried from a previous run). Default 0. */
  seedCtl?: number;
  seedAtl?: number;
  /** Extend the series this many days past the last activity (for projections). */
  throughDate?: string;
}

/**
 * Build the Performance Management Chart series from a daily-load series.
 * CTL/ATL are EWMAs of TSS; TSB (form) is yesterday's CTL - ATL.
 */
export function buildPmcSeries(dailyLoads: DailyLoad[], options: PmcOptions = {}): PmcPoint[] {
  if (dailyLoads.length === 0) return [];
  const ctlDays = options.ctlDays ?? 42;
  const atlDays = options.atlDays ?? 7;
  const ctlAlpha = 1 - Math.exp(-1 / ctlDays);
  const atlAlpha = 1 - Math.exp(-1 / atlDays);

  const tssByDate = new Map<string, number>();
  for (const d of dailyLoads) tssByDate.set(d.date, d.tss);

  const start = dailyLoads[0].date;
  const lastActivity = dailyLoads[dailyLoads.length - 1].date;
  const end =
    options.throughDate && options.throughDate > lastActivity ? options.throughDate : lastActivity;

  let ctl = options.seedCtl ?? 0;
  let atl = options.seedAtl ?? 0;
  const series: PmcPoint[] = [];
  for (const date of eachDay(start, end)) {
    const prevCtl = ctl;
    const prevAtl = atl;
    const tss = tssByDate.get(date) ?? 0;
    ctl = prevCtl + ctlAlpha * (tss - prevCtl);
    atl = prevAtl + atlAlpha * (tss - prevAtl);
    series.push({
      date,
      ctl: Number(ctl.toFixed(2)),
      atl: Number(atl.toFixed(2)),
      // Form is "freshness at the start of the day" = yesterday's balance.
      tsb: Number((prevCtl - prevAtl).toFixed(2)),
    });
  }
  return series;
}

export function latestPmc(series: PmcPoint[]): PmcPoint | undefined {
  return series[series.length - 1];
}

/** CTL change per week over the last `weeks` weeks of the series (ramp rate). */
export function rampRatePerWeek(series: PmcPoint[], weeks = 1): number {
  if (series.length < 2) return 0;
  const last = series[series.length - 1];
  const daysBack = Math.min(series.length - 1, weeks * 7);
  const prior = series[series.length - 1 - daysBack];
  const weeksElapsed = daysBack / 7;
  return weeksElapsed > 0 ? Number(((last.ctl - prior.ctl) / weeksElapsed).toFixed(2)) : 0;
}
