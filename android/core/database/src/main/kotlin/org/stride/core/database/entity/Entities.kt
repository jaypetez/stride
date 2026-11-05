package org.stride.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An activity summary, keyed by the Garmin activity id (upserts are idempotent). */
@Entity(tableName = "activity", indices = [Index("start_epoch_sec"), Index("local_date")])
data class ActivityEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val sport: String,
    @ColumnInfo(name = "start_epoch_sec") val startEpochSec: Long,
    /** ISO local date the activity started on, in its own time zone (for daily load buckets). */
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "time_zone") val timeZone: String?,
    @ColumnInfo(name = "duration_sec") val durationSec: Double,
    @ColumnInfo(name = "moving_time_sec") val movingTimeSec: Double?,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "avg_hr") val avgHr: Double?,
    @ColumnInfo(name = "max_hr") val maxHr: Double?,
    @ColumnInfo(name = "avg_speed_mps") val avgSpeedMps: Double?,
    @ColumnInfo(name = "max_speed_mps") val maxSpeedMps: Double?,
    @ColumnInfo(name = "avg_cadence_spm") val avgCadenceSpm: Double?,
    val calories: Double?,
    val source: String,
    @ColumnInfo(name = "has_streams") val hasStreams: Boolean
)

@Entity(
    tableName = "lap",
    primaryKeys = ["activity_id", "lap_index"],
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LapEntity(
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "lap_index") val index: Int,
    @ColumnInfo(name = "start_offset_sec") val startOffsetSec: Double,
    @ColumnInfo(name = "duration_sec") val durationSec: Double,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "avg_hr") val avgHr: Double?,
    @ColumnInfo(name = "max_hr") val maxHr: Double?,
    @ColumnInfo(name = "avg_speed_mps") val avgSpeedMps: Double?,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "avg_cadence_spm") val avgCadenceSpm: Double?
)

/**
 * Per-sample streams, one row per activity. Each channel is a packed
 * little-endian float32 array ([StreamCodec]); a missing channel is NULL.
 */
@Entity(
    tableName = "stream",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StreamEntity(
    @PrimaryKey @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "time_sec", typeAffinity = ColumnInfo.BLOB) val timeSec: ByteArray,
    @ColumnInfo(name = "heart_rate", typeAffinity = ColumnInfo.BLOB) val heartRate: ByteArray?,
    @ColumnInfo(name = "speed_mps", typeAffinity = ColumnInfo.BLOB) val speedMps: ByteArray?,
    @ColumnInfo(name = "distance_m", typeAffinity = ColumnInfo.BLOB) val distanceM: ByteArray?,
    @ColumnInfo(name = "altitude_m", typeAffinity = ColumnInfo.BLOB) val altitudeM: ByteArray?,
    @ColumnInfo(name = "cadence_spm", typeAffinity = ColumnInfo.BLOB) val cadenceSpm: ByteArray?
) {
    override fun equals(other: Any?): Boolean =
        other is StreamEntity && activityId == other.activityId && timeSec.contentEquals(other.timeSec)

    override fun hashCode(): Int = activityId.hashCode()
}

/** One calendar day of Garmin Health data, keyed by ISO date. */
@Entity(tableName = "daily_wellness")
data class DailyWellnessEntity(
    @PrimaryKey val date: String,
    @ColumnInfo(name = "resting_hr") val restingHr: Int?,
    @ColumnInfo(name = "hrv_last_night_ms") val hrvLastNightMs: Double?,
    @ColumnInfo(name = "hrv_weekly_avg_ms") val hrvWeeklyAvgMs: Double?,
    @ColumnInfo(name = "hrv_status") val hrvStatus: String?,
    @ColumnInfo(name = "sleep_sec") val sleepSec: Int?,
    @ColumnInfo(name = "sleep_score") val sleepScore: Int?,
    @ColumnInfo(name = "stress_avg") val stressAvg: Int?,
    @ColumnInfo(name = "body_battery_high") val bodyBatteryHigh: Int?,
    @ColumnInfo(name = "body_battery_low") val bodyBatteryLow: Int?,
    val steps: Int?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val source: String
)

/** Everything core:science computed for one activity (MVP-012). Screens only read this. */
@Entity(
    tableName = "activity_metrics",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ActivityMetricsEntity(
    @PrimaryKey @ColumnInfo(name = "activity_id") val activityId: Long,
    val load: Double,
    /** rtss | trimp | duration — which method produced [load]. */
    @ColumnInfo(name = "load_method") val loadMethod: String,
    @ColumnInfo(name = "ngp_mps") val ngpMps: Double?,
    @ColumnInfo(name = "intensity_factor") val intensityFactor: Double?,
    val trimp: Double?,
    /** Seconds in HR zones 1..5 as a comma-separated list, or NULL without HR. */
    @ColumnInfo(name = "hr_zone_seconds") val hrZoneSeconds: String?,
    @ColumnInfo(name = "efficiency_factor") val efficiencyFactor: Double?,
    @ColumnInfo(name = "decoupling_pct") val decouplingPct: Double?,
    /** VDOT estimated from this effort when it qualifies (runs only). */
    val vdot: Double?,
    @ColumnInfo(name = "engine_version") val engineVersion: Int,
    /** Hash of the anchors used; a change triggers a full recompute. */
    @ColumnInfo(name = "anchors_hash") val anchorsHash: String
)

/** The daily load series with PMC (CTL/ATL/TSB) and ACWR, rebuilt from the earliest changed date. */
@Entity(tableName = "daily_load")
data class DailyLoadEntity(
    @PrimaryKey val date: String,
    val load: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    @ColumnInfo(name = "acute_load") val acuteLoad: Double?,
    @ColumnInfo(name = "chronic_load") val chronicLoad: Double?,
    val acwr: Double?
)

/** Resumable sync bookkeeping (MVP-008). Single row, id = 1. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** Oldest date the backfill must reach. */
    @ColumnInfo(name = "backfill_target_date") val backfillTargetDate: String?,
    /** Oldest date already fully fetched; the backfill resumes before it. */
    @ColumnInfo(name = "backfill_cursor_date") val backfillCursorDate: String?,
    @ColumnInfo(name = "backfill_complete") val backfillComplete: Boolean,
    @ColumnInfo(name = "last_success_epoch_sec") val lastSuccessEpochSec: Long?
)
