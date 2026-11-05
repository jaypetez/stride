package org.stride.core.garmin.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire shapes from docs/garmin/personal-api.md. Every field is nullable and
 * unknown keys are ignored: Garmin's live API differs from its schema in places.
 */

@Serializable
data class ActivityTypeDto(val typeId: Int? = null, val typeKey: String? = null, val parentTypeId: Int? = null)

@Serializable
data class ActivityDto(
    val activityId: Long,
    val activityName: String? = null,
    val startTimeGMT: String? = null,
    /** Wall-clock start where the run happened; with the UTC start it gives the recorded offset (AUD-029). */
    val startTimeLocal: String? = null,
    val beginTimestamp: Long? = null,
    val activityType: ActivityTypeDto? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val movingDuration: Double? = null,
    val elevationGain: Double? = null,
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val averageHR: Double? = null,
    val maxHR: Double? = null,
    val averageRunningCadenceInStepsPerMinute: Double? = null,
    val calories: Double? = null
)

@Serializable
data class TimeZoneUnitDto(val timeZone: String? = null, val unitKey: String? = null)

@Serializable
data class ActivitySummaryDto(val startTimeGMT: String? = null, val startTimeLocal: String? = null)

@Serializable
data class ActivityDetailDto(
    val activityId: Long,
    val activityName: String? = null,
    val activityTypeDTO: ActivityTypeDto? = null,
    val timeZoneUnitDTO: TimeZoneUnitDto? = null,
    val summaryDTO: ActivitySummaryDto? = null
)

@Serializable
data class DailiesEnvelope(val data: List<DailySummaryDto> = emptyList())

@Serializable
data class DailySummaryDto(
    val calendarDate: String,
    val heartRate: DailyHeartRateDto? = null,
    val stress: DailyStressDto? = null,
    val bodyBattery: BodyBatteryDto? = null,
    val movement: MovementDto? = null
)

@Serializable
data class DailyHeartRateDto(val restingValue: Int? = null, val minValue: Int? = null, val maxValue: Int? = null)

@Serializable
data class DailyStressDto(val avgLevel: Int? = null, val maxLevel: Int? = null)

@Serializable
data class BodyBatteryDto(
    val min: Int? = null,
    val max: Int? = null,
    val charged: Int? = null,
    val drained: Int? = null,
    val latest: Int? = null
)

@Serializable
data class MovementDto(val steps: CountDto? = null)

@Serializable
data class CountDto(val value: Int? = null, val goal: Int? = null)

@Serializable
data class SleepDto(
    val calendarDate: String,
    val sleepTimeSeconds: Int? = null,
    val sleepScores: SleepScoresDto? = null,
    val avgHeartRate: Double? = null
)

@Serializable
data class SleepScoresDto(val overall: ScoreDto? = null)

@Serializable
data class ScoreDto(val value: Int? = null, val qualifierKey: String? = null)

@Serializable
data class HrvEnvelope(val hrvSummaries: List<HrvSummaryDto> = emptyList())

@Serializable
data class HrvSummaryDto(
    val calendarDate: String,
    val lastNightAvg: Double? = null,
    val weeklyAvg: Double? = null,
    val status: String? = null
)

@Serializable
data class StressDayDto(val calendarDate: String, val values: StressValuesDto? = null)

@Serializable
data class StressValuesDto(val overallStressLevel: Int? = null)

@Serializable
data class WeightEnvelope(val dailyWeightSummaries: List<WeightDayDto> = emptyList())

@Serializable
data class WeightDayDto(val summaryDate: String, val latestWeight: WeightSampleDto? = null)

/** `weight` arrives in **grams** despite the schema saying kg. */
@Serializable
data class WeightSampleDto(@SerialName("weight") val weightGrams: Double? = null)
