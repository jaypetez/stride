package org.stride.core.model

import kotlinx.serialization.Serializable

/** How an activity's training load was computed (packages/core load fallback chain). */
@Serializable
enum class LoadMethod(val label: String) {
    RTSS("rTSS (pace)"),
    TRIMP("TRIMP (heart rate)"),
    DURATION("Duration estimate"),
}

/** Everything core:science computed for one activity (MVP-012). */
@Serializable
data class ActivityMetrics(
    val activityId: Long,
    val load: Double,
    val loadMethod: LoadMethod,
    val ngpMps: Double? = null,
    val intensityFactor: Double? = null,
    val trimp: Double? = null,
    /** Seconds in HR zones 1..5, or null without heart rate. */
    val hrZoneSeconds: List<Double>? = null,
    val efficiencyFactor: Double? = null,
    val decouplingPct: Double? = null,
    val vdot: Double? = null,
)

/** A day on the Performance Management Chart plus the EWMA-ACWR. */
@Serializable
data class DailyLoadPoint(
    val date: String,
    val load: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val acwr: Double? = null,
)

/** Form bands from GOAL.md §7, keyed off TSB. */
@Serializable
enum class FormBand(val label: String, val description: String) {
    FRESH("Fresh", "Well rested — a good day for a key session or a race."),
    NEUTRAL("Neutral", "Balanced load and recovery."),
    PRODUCTIVE("Productive", "Absorbing training — fitness is building."),
    OVERREACHING("Overreaching", "Fatigue is high — prioritise recovery."),
}

/** An activity with its computed metrics (null until the recompute pipeline has run). */
@Serializable
data class ActivityWithMetrics(val activity: Activity, val metrics: ActivityMetrics?)

/** Everything the activity detail screen shows. */
data class ActivityDetail(
    val activity: Activity,
    val metrics: ActivityMetrics?,
    val laps: List<Lap>,
    val streams: ActivityStreams?,
)
