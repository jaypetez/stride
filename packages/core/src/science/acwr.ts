import type { AcwrFlag, AcwrPoint, DailyLoad } from '@stride/schemas';
import { eachDay } from './dates';

function flagFor(acwr: number, chronic: number): AcwrFlag {
  if (chronic < 1) return 'ok'; // not enough history to judge
  if (acwr < 0.8) return 'low';
  if (acwr <= 1.3) return 'ok';
  if (acwr <= 1.5) return 'high';
  return 'very_high';
}

export interface AcwrOptions {
  acuteDays?: number;
  chronicDays?: number;
}

/**
 * EWMA acute:chronic workload ratio (Williams/Murray 2017) — an injury-risk
 * guardrail. lambda = 2/(N+1); acute N=7, chronic N=28. Sweet spot 0.8-1.3;
 * >1.5 is associated with elevated injury risk.
 */
export function buildAcwrSeries(dailyLoads: DailyLoad[], options: AcwrOptions = {}): AcwrPoint[] {
  if (dailyLoads.length === 0) return [];
  const acuteN = options.acuteDays ?? 7;
  const chronicN = options.chronicDays ?? 28;
  const acuteLambda = 2 / (acuteN + 1);
  const chronicLambda = 2 / (chronicN + 1);

  const tssByDate = new Map<string, number>();
  for (const d of dailyLoads) tssByDate.set(d.date, d.tss);

  const start = dailyLoads[0].date;
  const end = dailyLoads[dailyLoads.length - 1].date;

  let acute = 0;
  let chronic = 0;
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
      flag: flagFor(acwr, chronic),
    });
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
