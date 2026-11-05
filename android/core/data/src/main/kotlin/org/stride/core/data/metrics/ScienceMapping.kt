package org.stride.core.data.metrics

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.stride.core.model.Activity
import org.stride.core.model.ActivityStreams
import org.stride.core.model.AthleteProfile as AppProfile
import org.stride.core.model.Sex as AppSex
import org.stride.core.model.SportType
import org.stride.core.model.UnitSystem
import org.stride.core.science.model.Activity as SciActivity
import org.stride.core.science.model.ActivitySource
import org.stride.core.science.model.ActivityStreams as SciStreams
import org.stride.core.science.model.AthleteProfile as SciProfile
import org.stride.core.science.model.Sex as SciSex
import org.stride.core.science.model.SportType as SciSport
import org.stride.core.science.model.Units as SciUnits

/*
 * App models → core:science models. The science port mirrors packages/core,
 * whose activity shape follows Strava conventions: running cadence in
 * strides/min and `startDateLocal` as the local wall-clock time.
 */

internal fun SportType.toScience(): SciSport = when (this) {
    SportType.RUN -> SciSport.RUN
    SportType.TRAIL_RUN -> SciSport.TRAIL_RUN
    SportType.TREADMILL_RUN -> SciSport.TREADMILL_RUN
    SportType.WALK -> SciSport.WALK
    SportType.HIKE -> SciSport.HIKE
    else -> SciSport.OTHER
}

/** Replaces NaN gaps by carrying the last valid value forward (and the first one backward). */
internal fun DoubleArray.filled(): List<Double>? {
    val firstValid = indexOfFirst { !it.isNaN() }
    if (firstValid < 0) return null
    var last = this[firstValid]
    return map { v ->
        if (v.isNaN()) {
            last
        } else {
            last = v
            v
        }
    }
}

internal fun ActivityStreams.toScience(isRun: Boolean): SciStreams = SciStreams(
    time = timeSec.toList(),
    distance = distanceM?.filled(),
    altitude = altitudeM?.filled(),
    velocitySmooth = speedMps?.filled(),
    heartrate = heartRate?.filled(),
    cadence = cadenceSpm?.filled()?.map { if (isRun) it / 2 else it }
)

internal fun Activity.toScience(streams: ActivityStreams?, fallbackZone: ZoneId = ZoneOffset.UTC): SciActivity {
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
    val start = Instant.ofEpochSecond(startEpochSec)
    val local = start.atZone(zone).toLocalDateTime().withNano(0)
    val sciStreams = streams?.toScience(sport.isRun)
    return SciActivity(
        id = id.toString(),
        source = ActivitySource.UPLOAD,
        sportType = sport.toScience(),
        name = name,
        startDate = start.toString(),
        startDateLocal = "${local}Z",
        timezone = timeZone,
        distance = distanceM,
        movingTime = movingTimeSec ?: durationSec,
        elapsedTime = durationSec,
        totalElevationGain = elevationGainM ?: 0.0,
        averageSpeed = avgSpeedMps,
        maxSpeed = maxSpeedMps,
        averageHeartrate = avgHr,
        maxHeartrate = maxHr,
        hasHeartrate = avgHr != null || sciStreams?.heartrate != null,
        averageCadence = avgCadenceSpm?.let { if (sport.isRun) it / 2 else it },
        trainer = sport == SportType.TREADMILL_RUN,
        streams = sciStreams
    )
}

/** The user's profile with their explicit overrides (which always win over estimates). */
internal fun AppProfile.toScience(today: java.time.LocalDate): SciProfile = SciProfile(
    name = name.ifBlank { null },
    sex = when (sex) {
        AppSex.FEMALE -> SciSex.FEMALE
        AppSex.MALE -> SciSex.MALE
        AppSex.UNSPECIFIED -> SciSex.UNSPECIFIED
    },
    age = birthYear?.let { today.year - it },
    units = if (unitSystem == UnitSystem.IMPERIAL) SciUnits.IMPERIAL else SciUnits.METRIC,
    maxHr = maxHr?.toDouble(),
    restingHr = restingHr?.toDouble(),
    lthr = thresholdHr?.toDouble(),
    thresholdSpeedMps = thresholdPaceSecPerKm?.takeIf { it > 0 }?.let { 1000.0 / it }
)
