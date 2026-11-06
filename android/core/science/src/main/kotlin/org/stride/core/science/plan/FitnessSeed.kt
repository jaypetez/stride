package org.stride.core.science.plan

import kotlinx.serialization.Serializable
import org.stride.core.science.model.Activity
import org.stride.core.science.model.SportType
import org.stride.core.science.util.DateKeys
import org.stride.core.science.util.toFixed
import org.stride.core.science.vdot.vdotFromEffort

/*
 * Fitness seed (MVP-025). Mirrors packages/core/src/science/seed.ts; the golden seed.json pins it.
 */

/** Days in the volume window (the 4 weeks ending on the reference day). */
const val SEED_WINDOW_DAYS = 28

/** Days in the VDOT window (the 8 weeks ending on the reference day). */
const val SEED_VDOT_WINDOW_DAYS = 56

/** Fewer runs than this in the volume window means no seed. */
const val SEED_MIN_RUNS = 3

/** A run shorter than this (metres) makes a poor VDOT estimate. */
const val SEED_MIN_VDOT_DISTANCE_M = 3000.0

/** A run shorter than this (seconds) makes a poor VDOT estimate. */
const val SEED_MIN_VDOT_DURATION_SEC = 600.0

/** What the athlete has run lately; seeds a plan's volume, long run and paces. Mirrors schemas#FitnessSeed. */
@Serializable
data class FitnessSeed(
    /** Average running minutes per week over the last 28 days (1 dp). */
    val weeklyMinutes: Double,
    /** Longest single run (moving time, minutes) in the last 28 days (1 dp). */
    val longestRunMin: Double,
    /** Best VDOT from qualifying runs in the last 56 days (1 dp); null when none qualify. */
    val vdot: Double? = null,
    /** Runs in the last 28 days. */
    val runCount: Int
)

private val RUN_TYPES = setOf(SportType.RUN, SportType.TRAIL_RUN, SportType.TREADMILL_RUN)

private fun qualifiesForVdot(a: Activity): Boolean =
    !a.manual && !a.trainer && a.movingTime >= SEED_MIN_VDOT_DURATION_SEC && a.distance >= SEED_MIN_VDOT_DISTANCE_M

/**
 * Summarizes recent running: runs only, bucketed by local day (startDateLocal, else startDate). Volume window =
 * the 28 days ending on [asOf] (weeklyMinutes = moving time / 4, longestRunMin = longest run); vdot = best VDOT
 * over the 56 days ending on [asOf] from runs that are not manual or treadmill, >= 10 min and >= 3 km. Null when
 * the volume window has fewer than 3 runs. Mirrors packages/core/src/science/seed.ts#fitnessSeed.
 */
fun fitnessSeed(activities: List<Activity>, asOf: String): FitnessSeed? {
    val end = DateKeys.toDateKey(asOf)
    val volumeStart = DateKeys.addDays(end, -(SEED_WINDOW_DAYS - 1))
    val vdotStart = DateKeys.addDays(end, -(SEED_VDOT_WINDOW_DAYS - 1))
    var totalSec = 0.0
    var longestSec = 0.0
    var runCount = 0
    var bestVdot = 0.0
    val recentRuns = activities.filter { a ->
        val day = DateKeys.toDateKey(a.startDateLocal ?: a.startDate)
        a.sportType in RUN_TYPES && day <= end && day >= vdotStart
    }
    for (a in recentRuns) {
        val day = DateKeys.toDateKey(a.startDateLocal ?: a.startDate)
        if (day >= volumeStart) {
            runCount++
            totalSec += a.movingTime
            if (a.movingTime > longestSec) longestSec = a.movingTime
        }
        if (qualifiesForVdot(a)) {
            val vdot = vdotFromEffort(a.distance, a.movingTime)
            if (vdot > bestVdot) bestVdot = vdot
        }
    }
    if (runCount < SEED_MIN_RUNS) return null
    return FitnessSeed(
        weeklyMinutes = toFixed(totalSec / 60 / 4, 1),
        longestRunMin = toFixed(longestSec / 60, 1),
        vdot = if (bestVdot > 0) toFixed(bestVdot, 1) else null,
        runCount = runCount
    )
}
