package org.stride.core.science.anchors

import kotlinx.serialization.Serializable
import org.stride.core.science.model.Activity
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.SportType
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.truthy
import org.stride.core.science.vdot.thresholdSpeedFromVdot
import org.stride.core.science.vdot.vdotFromEffort

/*
 * Physiological anchors. Mirrors packages/core/src/science/anchors.ts. Profile values equal to 0 count as
 * missing (JS truthiness), exactly like the TS.
 */

/** Profile max HR, else Tanaka 208 - 0.7 x age, else null. Mirrors anchors.ts#resolveMaxHr. */
fun resolveMaxHr(profile: AthleteProfile): Double? = when {
    profile.maxHr.truthy() -> profile.maxHr
    profile.age.truthy() -> 208 - 0.7 * profile.age!!
    else -> null
}

/** Profile LTHR, else 0.9 x resolved max HR, else null. Mirrors anchors.ts#resolveLthr. */
fun resolveLthr(profile: AthleteProfile): Double? {
    if (profile.lthr.truthy()) return profile.lthr
    val max = resolveMaxHr(profile)
    return if (max.truthy()) 0.9 * max!! else null
}

/** Profile resting HR, else 60 (a null-only default: an explicit 0 is kept). Mirrors anchors.ts#resolveRestingHr. */
fun resolveRestingHr(profile: AthleteProfile): Double = profile.restingHr ?: 60.0

/** Profile threshold speed (m/s), else derived from VDOT, else null. Mirrors anchors.ts#resolveThresholdSpeed. */
fun resolveThresholdSpeed(profile: AthleteProfile): Double? = when {
    profile.thresholdSpeedMps.truthy() -> profile.thresholdSpeedMps
    profile.vdot.truthy() -> thresholdSpeedFromVdot(profile.vdot!!)
    else -> null
}

/** Anchors estimated from history; null means unknown. Mirrors anchors.ts#EstimatedAnchors. */
@Serializable
data class EstimatedAnchors(
    val vdot: Double? = null,
    val thresholdSpeedMps: Double? = null,
    val maxHr: Double? = null,
    val lthr: Double? = null
)

private val RUN_TYPES = setOf(SportType.RUN, SportType.TRAIL_RUN, SportType.TREADMILL_RUN)

/** Pace-trustworthy run of >= 10 min with distance (manual and treadmill efforts are skipped). */
private fun qualifiesForVdot(a: Activity): Boolean {
    val trustedRun = a.sportType in RUN_TYPES && !a.manual && !a.trainer
    return trustedRun && a.movingTime >= 600 && a.distance > 0
}

/**
 * Estimates anchors from history: VDOT/threshold from the best run (not manual/treadmill, >= 10 min, distance > 0),
 * max HR from the highest observed maxHeartrate across ALL activities, LTHR = 0.9 x that. Rounds vdot to 1 dp,
 * threshold to 3 dp, LTHR to 0 dp. Mirrors packages/core/src/science/anchors.ts#estimateAnchors.
 */
fun estimateAnchors(activities: List<Activity>): EstimatedAnchors {
    var bestVdot = 0.0
    var maxHrObserved = 0.0
    for (a in activities) {
        val maxHr = a.maxHeartrate
        if (maxHr.truthy() && maxHr!! > maxHrObserved) maxHrObserved = maxHr
        if (qualifiesForVdot(a)) {
            val vdot = vdotFromEffort(a.distance, a.movingTime)
            if (vdot > bestVdot) bestVdot = vdot
        }
    }
    return EstimatedAnchors(
        vdot = if (bestVdot > 0) toFixed(bestVdot, 1) else null,
        thresholdSpeedMps = if (bestVdot > 0) toFixed(thresholdSpeedFromVdot(bestVdot), 3) else null,
        maxHr = if (maxHrObserved > 0) maxHrObserved else null,
        lthr = if (maxHrObserved > 0) toFixed(0.9 * maxHrObserved, 0) else null
    )
}

/**
 * Fills the profile's MISSING anchors from [estimated]; anything the user set (threshold, VDOT, max HR, LTHR)
 * wins, and resting HR is never estimated. Mirrors the anchor merge in packages/core/src/sync.ts#syncStrava.
 */
fun applyEstimatedAnchors(profile: AthleteProfile, estimated: EstimatedAnchors, nowIso: String? = null): AthleteProfile = profile.copy(
    thresholdSpeedMps = profile.thresholdSpeedMps ?: estimated.thresholdSpeedMps,
    vdot = profile.vdot ?: estimated.vdot,
    maxHr = profile.maxHr ?: estimated.maxHr,
    lthr = profile.lthr ?: estimated.lthr,
    anchorsUpdatedAt = nowIso ?: profile.anchorsUpdatedAt,
    updatedAt = nowIso ?: profile.updatedAt
)
