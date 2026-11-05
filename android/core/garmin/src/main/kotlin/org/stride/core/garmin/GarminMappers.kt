package org.stride.core.garmin

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong
import org.stride.core.garmin.dto.ActivityDto
import org.stride.core.garmin.dto.DailySummaryDto
import org.stride.core.garmin.dto.HrvSummaryDto
import org.stride.core.garmin.dto.SleepDto
import org.stride.core.garmin.dto.StressDayDto
import org.stride.core.garmin.dto.WeightDayDto
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.SportType

private const val QUARTER_HOUR_SEC = 900.0
private const val MAX_OFFSET_SEC = 18 * 3600L

/**
 * Parses Garmin's local/GMT timestamps: `2026-11-04 07:05:00` (list endpoint) or
 * `2026-11-04T07:05:00.0` (detail endpoint). Null for anything else, never throws (AUD-028).
 */
fun parseGarminDateTime(text: String?): LocalDateTime? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { LocalDateTime.parse(trimmed.replace(' ', 'T')) }.getOrNull()
}

/**
 * The fixed UTC offset (e.g. `+09:00`, `Z`) where the activity was recorded, from Garmin's
 * `startTimeLocal` and the UTC start (AUD-029). Rounded to the quarter hour; null when unknown.
 */
internal fun recordedOffsetId(startTimeLocal: String?, startEpochSec: Long): String? {
    val local = parseGarminDateTime(startTimeLocal) ?: return null
    val diff = local.toEpochSecond(ZoneOffset.UTC) - startEpochSec
    val rounded = (diff / QUARTER_HOUR_SEC).roundToLong() * QUARTER_HOUR_SEC.toLong()
    if (abs(rounded) > MAX_OFFSET_SEC) return null
    return ZoneOffset.ofTotalSeconds(rounded.toInt()).id
}

/** Garmin `typeKey` → Stride sport. */
fun sportFromTypeKey(typeKey: String?): SportType = when (typeKey?.lowercase()) {
    "running", "street_running", "track_running", "ultra_run", "virtual_run", "obstacle_run" -> SportType.RUN
    "trail_running" -> SportType.TRAIL_RUN
    "treadmill_running", "indoor_running" -> SportType.TREADMILL_RUN
    "walking", "casual_walking", "speed_walking" -> SportType.WALK
    "hiking" -> SportType.HIKE
    "cycling", "road_biking", "mountain_biking", "gravel_cycling", "indoor_cycling", "virtual_ride" -> SportType.CYCLE
    "lap_swimming", "open_water_swimming", "swimming" -> SportType.SWIM
    "strength_training" -> SportType.STRENGTH
    else -> SportType.OTHER
}

/**
 * Maps a list/detail DTO to an [Activity], or null when it has no usable start time:
 * a missing or unparseable timestamp would otherwise become a 1970 run (AUD-028).
 * [timeZone] (IANA, from the detail endpoint) wins; otherwise the zone is the offset
 * implied by `startTimeLocal`, so runs abroad keep their local date (AUD-029).
 */
fun ActivityDto.toActivityOrNull(timeZone: String? = null): Activity? {
    val start = beginTimestamp?.takeIf { it > 0 }?.let { it / 1000 }
        ?: parseGarminDateTime(startTimeGMT)?.toEpochSecond(ZoneOffset.UTC)?.takeIf { it > 0 }
        ?: return null
    return Activity(
        id = activityId,
        name = activityName?.takeIf { it.isNotBlank() } ?: "Activity",
        sport = sportFromTypeKey(activityType?.typeKey),
        startEpochSec = start,
        timeZone = timeZone ?: recordedOffsetId(startTimeLocal, start),
        durationSec = duration ?: 0.0,
        movingTimeSec = movingDuration,
        distanceM = distance ?: 0.0,
        elevationGainM = elevationGain,
        avgHr = averageHR,
        maxHr = maxHR,
        avgSpeedMps = averageSpeed,
        maxSpeedMps = maxSpeed,
        avgCadenceSpm = averageRunningCadenceInStepsPerMinute,
        calories = calories,
        source = DataSource.GARMIN
    )
}

/** Merges the separate Health endpoints into one [DailyWellness] per calendar date. */
fun mergeWellness(
    dailies: List<DailySummaryDto>,
    sleeps: List<SleepDto>,
    hrv: List<HrvSummaryDto>,
    stress: List<StressDayDto>,
    weights: List<WeightDayDto>
): List<DailyWellness> {
    val byDate = sortedMapOf<String, DailyWellness>()
    fun update(date: String, f: (DailyWellness) -> DailyWellness) {
        byDate[date] = f(byDate[date] ?: DailyWellness(date = date))
    }
    dailies.forEach { d ->
        update(d.calendarDate) {
            it.copy(
                restingHr = d.heartRate?.restingValue ?: it.restingHr,
                stressAvg = d.stress?.avgLevel?.takeIf { s -> s >= 0 } ?: it.stressAvg,
                bodyBatteryHigh = d.bodyBattery?.max ?: it.bodyBatteryHigh,
                bodyBatteryLow = d.bodyBattery?.min ?: it.bodyBatteryLow,
                steps = d.movement?.steps?.value ?: it.steps
            )
        }
    }
    sleeps.forEach { s ->
        update(s.calendarDate) {
            it.copy(
                sleepSec = s.sleepTimeSeconds ?: it.sleepSec,
                sleepScore =
                    s.sleepScores?.overall?.value ?: it.sleepScore
            )
        }
    }
    hrv.forEach { h ->
        update(h.calendarDate) { it.copy(hrvLastNightMs = h.lastNightAvg, hrvWeeklyAvgMs = h.weeklyAvg, hrvStatus = h.status) }
    }
    stress.forEach { s ->
        val level = s.values?.overallStressLevel?.takeIf { it >= 0 }
        if (level != null) update(s.calendarDate) { it.copy(stressAvg = it.stressAvg ?: level) }
    }
    weights.forEach { w ->
        val grams = w.latestWeight?.weightGrams
        if (grams != null) update(w.summaryDate) { it.copy(weightKg = grams / 1000.0) }
    }
    return byDate.values.toList()
}
