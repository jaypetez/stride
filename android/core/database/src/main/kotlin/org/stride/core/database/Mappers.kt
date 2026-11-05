package org.stride.core.database

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.stride.core.database.entity.ActivityEntity
import org.stride.core.database.entity.DailyWellnessEntity
import org.stride.core.database.entity.LapEntity
import org.stride.core.database.entity.StreamEntity
import org.stride.core.model.Activity
import org.stride.core.model.ActivityStreams
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.Lap
import org.stride.core.model.SportType

/** The calendar date an activity counts towards: its start in its own zone (UTC if unknown). */
fun Activity.localDate(fallbackZone: ZoneId = ZoneOffset.UTC): String {
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
    return Instant.ofEpochSecond(startEpochSec).atZone(zone).toLocalDate().toString()
}

fun Activity.toEntity(fallbackZone: ZoneId = ZoneOffset.UTC) = ActivityEntity(
    id = id,
    name = name,
    sport = sport.name,
    startEpochSec = startEpochSec,
    localDate = localDate(fallbackZone),
    timeZone = timeZone,
    durationSec = durationSec,
    movingTimeSec = movingTimeSec,
    distanceM = distanceM,
    elevationGainM = elevationGainM,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgCadenceSpm = avgCadenceSpm,
    calories = calories,
    source = source.name,
    hasStreams = hasStreams
)

fun ActivityEntity.toModel() = Activity(
    id = id,
    name = name,
    sport = runCatching { SportType.valueOf(sport) }.getOrDefault(SportType.OTHER),
    startEpochSec = startEpochSec,
    timeZone = timeZone,
    durationSec = durationSec,
    movingTimeSec = movingTimeSec,
    distanceM = distanceM,
    elevationGainM = elevationGainM,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgCadenceSpm = avgCadenceSpm,
    calories = calories,
    source = runCatching { DataSource.valueOf(source) }.getOrDefault(DataSource.GARMIN),
    hasStreams = hasStreams
)

fun Lap.toEntity() =
    LapEntity(activityId, index, startOffsetSec, durationSec, distanceM, avgHr, maxHr, avgSpeedMps, elevationGainM, avgCadenceSpm)

fun LapEntity.toModel() =
    Lap(activityId, index, startOffsetSec, durationSec, distanceM, avgHr, maxHr, avgSpeedMps, elevationGainM, avgCadenceSpm)

fun ActivityStreams.toEntity() = StreamEntity(
    activityId = activityId,
    sampleCount = size,
    timeSec = StreamCodec.encode(timeSec),
    heartRate = heartRate?.let(StreamCodec::encode),
    speedMps = speedMps?.let(StreamCodec::encode),
    distanceM = distanceM?.let(StreamCodec::encode),
    altitudeM = altitudeM?.let(StreamCodec::encode),
    cadenceSpm = cadenceSpm?.let(StreamCodec::encode)
)

fun StreamEntity.toModel() = ActivityStreams(
    activityId = activityId,
    timeSec = StreamCodec.decode(timeSec),
    heartRate = heartRate?.let(StreamCodec::decode),
    speedMps = speedMps?.let(StreamCodec::decode),
    distanceM = distanceM?.let(StreamCodec::decode),
    altitudeM = altitudeM?.let(StreamCodec::decode),
    cadenceSpm = cadenceSpm?.let(StreamCodec::decode)
)

fun DailyWellness.toEntity() = DailyWellnessEntity(
    date = date,
    restingHr = restingHr,
    hrvLastNightMs = hrvLastNightMs,
    hrvWeeklyAvgMs = hrvWeeklyAvgMs,
    hrvStatus = hrvStatus,
    sleepSec = sleepSec,
    sleepScore = sleepScore,
    stressAvg = stressAvg,
    bodyBatteryHigh = bodyBatteryHigh,
    bodyBatteryLow = bodyBatteryLow,
    steps = steps,
    weightKg = weightKg,
    source = source.name
)

fun DailyWellnessEntity.toModel() = DailyWellness(
    date = date,
    restingHr = restingHr,
    hrvLastNightMs = hrvLastNightMs,
    hrvWeeklyAvgMs = hrvWeeklyAvgMs,
    hrvStatus = hrvStatus,
    sleepSec = sleepSec,
    sleepScore = sleepScore,
    stressAvg = stressAvg,
    bodyBatteryHigh = bodyBatteryHigh,
    bodyBatteryLow = bodyBatteryLow,
    steps = steps,
    weightKg = weightKg,
    source = runCatching { DataSource.valueOf(source) }.getOrDefault(DataSource.GARMIN)
)
