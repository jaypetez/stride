package org.stride.core.model

import kotlinx.serialization.Serializable

/** Where a record came from. Demo data lives in a separate database (MVP-020). */
@Serializable
enum class DataSource {
    GARMIN,
    DEMO
}

/** Activity types Stride distinguishes. Only runs feed run-specific metrics (VDOT, pace zones). */
@Serializable
enum class SportType(val label: String, val isRun: Boolean) {
    RUN("Run", true),
    TRAIL_RUN("Trail run", true),
    TREADMILL_RUN("Treadmill run", true),
    WALK("Walk", false),
    HIKE("Hike", false),
    CYCLE("Ride", false),
    SWIM("Swim", false),
    STRENGTH("Strength", false),
    OTHER("Other", false)
}

/**
 * An activity summary. Every quantity is SI: metres, seconds, metres/second,
 * beats/minute; `startEpochSec` is UTC.
 */
@Serializable
data class Activity(
    val id: Long,
    val name: String,
    val sport: SportType,
    val startEpochSec: Long,
    /**
     * Zone the activity was recorded in, used for "local day" bucketing: an IANA id
     * (`Europe/Berlin`) or, from Garmin's activity list, the fixed offset implied by
     * `startTimeLocal` (`+09:00`). Null falls back to the phone's zone.
     */
    val timeZone: String?,
    val durationSec: Double,
    val movingTimeSec: Double?,
    val distanceM: Double,
    val elevationGainM: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val avgCadenceSpm: Double?,
    val calories: Double?,
    val source: DataSource,
    /** True once the FIT file has been downloaded and parsed into laps and streams. */
    val hasStreams: Boolean = false
)

/** One lap / split from the FIT file. */
@Serializable
data class Lap(
    val activityId: Long,
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
    val distanceM: Double,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgSpeedMps: Double?,
    val elevationGainM: Double?,
    val avgCadenceSpm: Double?
)

/**
 * Per-sample streams decoded from the FIT file. Arrays are aligned with [timeSec]
 * (seconds since start); a missing channel is null, a missing sample is NaN.
 */
@Serializable
data class ActivityStreams(
    val activityId: Long,
    val timeSec: DoubleArray,
    val heartRate: DoubleArray? = null,
    val speedMps: DoubleArray? = null,
    val distanceM: DoubleArray? = null,
    val altitudeM: DoubleArray? = null,
    val cadenceSpm: DoubleArray? = null
) {
    val size: Int get() = timeSec.size

    override fun equals(other: Any?): Boolean = other is ActivityStreams &&
        activityId == other.activityId &&
        timeSec.contentEquals(other.timeSec) &&
        heartRate.contentEquals(other.heartRate) &&
        speedMps.contentEquals(other.speedMps) &&
        distanceM.contentEquals(other.distanceM) &&
        altitudeM.contentEquals(other.altitudeM) &&
        cadenceSpm.contentEquals(other.cadenceSpm)

    override fun hashCode(): Int {
        var result = activityId.hashCode()
        result = 31 * result + timeSec.contentHashCode()
        result = 31 * result + (heartRate?.contentHashCode() ?: 0)
        result = 31 * result + (speedMps?.contentHashCode() ?: 0)
        return result
    }
}
