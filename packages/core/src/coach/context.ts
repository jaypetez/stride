import type {
  Activity,
  AthleteProfile,
  CoachContext,
  DailyLoad,
  PlanPhase,
  RaceGoal,
} from '@stride/schemas';
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
  /**
   * Reference date (ISO date or datetime). Defaults to the latest activity, else
   * today. Pass the athlete's LOCAL date (`startDateLocal ?? startDate`) when
   * analyzing an activity, and the pinned clock (`resolveNowIso`) otherwise.
   */
  asOfDate?: string;
  /**
   * The durable daily-load series (GOAL.md §7 source of truth). Live callers
   * pass `store.loadDailyLoads()` so the PMC/ACWR outlive the 7-day raw cache;
   * demo/tests omit it and fall back to computing from `activities`. The recent
   * (≤7-day) windows below always read raw `activities`, which are still fresh.
   */
  dailyLoads?: DailyLoad[];
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

  // End the fitness/fatigue series ON the reference day: project it forward
  // after rest days (ATL decays, TSB rises) and truncate it when asOf is
  // before the latest load, so analyzing an older activity reports CTL/ATL/TSB
  // as of that activity, not today. Prefer the durable daily-load series when supplied (survives the 7-day raw
  // cache); otherwise derive it from the raw activities (demo/tests).
  const dailies = params.dailyLoads ?? toDailyLoads(activities, profile);
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

  // Only activities on or before the reference day: nothing from "the future"
  // relative to asOf may leak into the facts the coach reasons over.
  const recentActivities = sorted
    .filter((a) => toDateKey(a.startDateLocal ?? a.startDate) <= asOf)
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
