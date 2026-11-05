package org.stride.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Sex {
    FEMALE,
    MALE,
    UNSPECIFIED
}

/**
 * The local athlete profile (MVP-013). Optional overrides (max/resting/threshold
 * HR, threshold pace) take precedence over anchors estimated from activities,
 * exactly as in packages/core.
 */
@Serializable
data class AthleteProfile(
    val name: String = "",
    val birthYear: Int? = null,
    val sex: Sex = Sex.UNSPECIFIED,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val maxHr: Int? = null,
    val restingHr: Int? = null,
    val thresholdHr: Int? = null,
    /** Threshold pace in seconds per kilometre. */
    val thresholdPaceSecPerKm: Double? = null,
    val raceGoal: RaceGoal? = null
)

@Serializable
data class RaceGoal(
    val distanceM: Double,
    /** ISO date of the race. */
    val date: String,
    val targetTimeSec: Double? = null
)
