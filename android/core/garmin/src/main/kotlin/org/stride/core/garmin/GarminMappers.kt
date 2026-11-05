package org.stride.core.garmin

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

private val GMT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

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

fun ActivityDto.toActivity(timeZone: String? = null): Activity {
    val start = beginTimestamp?.let { it / 1000 }
        ?: startTimeGMT?.let { LocalDateTime.parse(it, GMT_FORMAT).toEpochSecond(ZoneOffset.UTC) }
        ?: 0L
    return Activity(
        id = activityId,
        name = activityName?.takeIf { it.isNotBlank() } ?: "Activity",
        sport = sportFromTypeKey(activityType?.typeKey),
        startEpochSec = start,
        timeZone = timeZone,
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
