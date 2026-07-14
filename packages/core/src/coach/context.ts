import type { Activity, AthleteProfile, CoachContext, PlanPhase, RaceGoal } from '@stride/schemas';
import {
  addDays,
  buildAcwrSeries,
  buildPmcSeries,
  computeActivityLoad,
  daysBetween,
  latestAcwr,
  latestPmc,
  mpsToSecPerKm,
  rampRatePerWeek,
  toDailyLoads,
  toDateKey,
  zoneDistribution,
} from '../science/index';

function derivePhase(daysToRace: number | undefined): PlanPhase | undefined {
  if (daysToRace === undefined || daysToRace < 0) return undefined;
  if (daysToRace <= 14) return 'taper';
  if (daysToRace <= 35) return 'peak';
  if (daysToRace <= 84) return 'build';
  return 'base';
}

export interface BuildContextParams {
  activities: Activity[];
  profile: AthleteProfile;
  goal?: RaceGoal;
  /** Reference date (ISO). Defaults to the latest activity, else today. */
  asOfDate?: string;
}

/**
 * Assemble the pre-computed "facts" bundle for the coach. Every number here is
 * computed deterministically; the LLM only reasons over this object.
 */
export function buildCoachContext(params: BuildContextParams): CoachContext {
  const { activities, profile, goal } = params;
  const sorted = [...activities].sort((a, b) => a.startDate.localeCompare(b.startDate));

  const latestDate = sorted.length
    ? toDateKey(sorted[sorted.length - 1].startDateLocal ?? sorted[sorted.length - 1].startDate)
    : toDateKey(params.asOfDate ?? new Date().toISOString());
  const asOf = params.asOfDate ? toDateKey(params.asOfDate) : latestDate;
  const weekStart = addDays(asOf, -6);

  // Project the fitness/fatigue series forward to the reference day so that,
  // after rest days, ATL has decayed and TSB has risen (fatigue is not frozen
  // at the last activity date). `throughDate` is a no-op when asOf <= latest.
  const dailies = toDailyLoads(activities, profile);
  const pmc = buildPmcSeries(dailies, { throughDate: asOf });
  const acwrSeries = buildAcwrSeries(dailies, { throughDate: asOf });
  const fitness = latestPmc(pmc);
  const acwr = latestAcwr(acwrSeries);
  const ramp = rampRatePerWeek(pmc, 2);

  const last7 = activities.filter((a) => {
    const d = toDateKey(a.startDateLocal ?? a.startDate);
    return d >= weekStart && d <= asOf;
  });
  const weeklyDistribution = zoneDistribution(last7, profile);
  const weeklyVolumeKm = Number((last7.reduce((s, a) => s + a.distance, 0) / 1000).toFixed(1));

  const recentActivities = [...sorted]
    .reverse()
    .slice(0, 10)
    .map((a) => {
      const load = computeActivityLoad(a, profile);
      const summary = {
        date: toDateKey(a.startDateLocal ?? a.startDate),
        name: a.name,
        sportType: a.sportType,
        distanceKm: Number((a.distance / 1000).toFixed(2)),
        durationSec: a.movingTime,
        tss: Number(load.tss.toFixed(1)),
        loadMethod: load.method,
        avgHr: a.averageHeartrate ? Math.round(a.averageHeartrate) : undefined,
        avgPaceSecPerKm:
          a.averageSpeed && a.averageSpeed > 0
            ? Math.round(mpsToSecPerKm(a.averageSpeed))
            : undefined,
      };
      return summary;
    });

  const daysToRace = goal?.date ? daysBetween(asOf, goal.date) : undefined;

  return {
    generatedAt: params.asOfDate ?? new Date().toISOString(),
    profile,
    fitness,
    acwr,
    rampRatePerWeek: ramp,
    weeklyDistribution,
    weeklyVolumeKm,
    recentActivities,
    goal,
    daysToRace,
    planPhase: derivePhase(daysToRace),
  };
}
