import type { AcwrFlag, AcwrPoint, DailyLoad } from '@stride/schemas';
import { eachDay } from './dates';
import { mean } from './util';

function flagFor(acwr: number): AcwrFlag {
  if (acwr < 0.8) return 'low';
  if (acwr <= 1.3) return 'ok';
  if (acwr <= 1.5) return 'high';
  return 'very_high';
}

export interface AcwrOptions {
  acuteDays?: number;
  chronicDays?: number;
  /**
   * Suppress the injury flag (force `ok`) until this many days of history have
   * accrued. ACWR is a ~4-week measure; before the chronic EWMA has stabilized
   * the ratio is meaningless, so flagging early produces false `very_high`
   * alarms. Defaults to `chronicDays`. TSB and CTL-ramp cover the early period.
   */
  warmupDays?: number;
  /** Extend the series this many days past the last activity (for projections). */
  throughDate?: string;
}

/**
 * EWMA acute:chronic workload ratio (Williams/Murray 2017) — an injury-risk
 * guardrail. lambda = 2/(N+1); acute N=7, chronic N=28. Sweet spot 0.8-1.3;
 * >1.5 is associated with elevated injury risk.
 *
 * Both EWMAs are seeded to the same short baseline (the mean of the first
 * `acuteDays` of load) so the ratio starts near 1 instead of spiking from a
 * zero seed, and flags are suppressed during the warm-up window (see
 * `warmupDays`). Together these remove the cold-start artifact that otherwise
 * flagged `very_high` for the first few weeks of any athlete's history.
 */
export function buildAcwrSeries(dailyLoads: DailyLoad[], options: AcwrOptions = {}): AcwrPoint[] {
  if (dailyLoads.length === 0) return [];
  const acuteN = options.acuteDays ?? 7;
  const chronicN = options.chronicDays ?? 28;
  const warmupDays = options.warmupDays ?? chronicN;
  const acuteLambda = 2 / (acuteN + 1);
  const chronicLambda = 2 / (chronicN + 1);

  const tssByDate = new Map<string, number>();
  for (const d of dailyLoads) tssByDate.set(d.date, d.tss);

  const start = dailyLoads[0].date;
  const lastActivity = dailyLoads[dailyLoads.length - 1].date;
  const end =
    options.throughDate && options.throughDate > lastActivity ? options.throughDate : lastActivity;

  // Seed both EWMAs to a short baseline so early ratios are ~1, not inflated.
  const seed = mean(dailyLoads.slice(0, acuteN).map((d) => d.tss));
  let acute = seed;
  let chronic = seed;
  let dayIndex = 0;
  const series: AcwrPoint[] = [];
  for (const date of eachDay(start, end)) {
    const tss = tssByDate.get(date) ?? 0;
    acute = tss * acuteLambda + (1 - acuteLambda) * acute;
    chronic = tss * chronicLambda + (1 - chronicLambda) * chronic;
    const acwr = chronic > 0 ? acute / chronic : 0;
    series.push({
      date,
      acwr: Number(acwr.toFixed(2)),
      acuteLoad: Number(acute.toFixed(2)),
      chronicLoad: Number(chronic.toFixed(2)),
      // ACWR needs ~4 weeks to be meaningful; don't cry wolf before then.
      flag: dayIndex < warmupDays ? 'ok' : flagFor(acwr),
    });
    dayIndex++;
  }
  return series;
}

export function latestAcwr(series: AcwrPoint[]): AcwrPoint | undefined {
  return series[series.length - 1];
}

/** Guardrail bounds for how much CTL may ramp per week, by experience. */
export function ctlRampCap(experience: 'beginner' | 'intermediate' | 'advanced'): number {
  switch (experience) {
    case 'beginner':
      return 4;
    case 'advanced':
      return 7;
    default:
      return 5.5;
  }
}
