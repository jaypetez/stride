package org.stride.core.science.load

import kotlin.math.exp
import kotlin.math.max
import kotlinx.serialization.Serializable
import org.stride.core.science.anchors.resolveLthr
import org.stride.core.science.anchors.resolveMaxHr
import org.stride.core.science.anchors.resolveRestingHr
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.model.Activity
import org.stride.core.science.model.ActivityStreams
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.LoadMethod
import org.stride.core.science.model.Sex
import org.stride.core.science.util.SECONDS_PER_HOUR
import org.stride.core.science.util.clamp
import org.stride.core.science.util.fourthPowerNorm
import org.stride.core.science.util.gradeAdjustFactor
import org.stride.core.science.util.interpolate
import org.stride.core.science.util.mean
import org.stride.core.science.util.rollingMeanByTime
import org.stride.core.science.util.timeDeltas
import org.stride.core.science.util.timesOrIndex
import org.stride.core.science.util.truthy

/** One activity's training load. Nothing is rounded. Mirrors packages/core/src/science/load.ts#LoadResult. */
@Serializable
data class LoadResult(
    val tss: Double,
    val method: LoadMethod,
    val intensityFactor: Double? = null,
    val ngpSpeedMps: Double? = null,
    val meanGapSpeedMps: Double? = null,
    val averageSpeedMps: Double? = null,
    val averageHr: Double? = null,
    val durationSec: Double,
    val distanceM: Double
)

/** Normalized Graded Pace and duration-weighted mean GAP, both as speeds (m/s). */
@Serializable
data class NgpResult(val ngpSpeedMps: Double, val meanGapSpeedMps: Double)

/** %LTHR -> intensity factor (HR is nonlinear vs pace). */
private val HR_IF_TABLE = listOf(
    0.5 to 0.5,
    0.6 to 0.55,
    0.7 to 0.65,
    0.8 to 0.75,
    0.85 to 0.8,
    0.9 to 0.85,
    0.95 to 0.92,
    1.0 to 1.0,
    1.05 to 1.06,
    1.1 to 1.12,
    1.2 to 1.2
)

private fun ifFromHrFraction(frac: Double): Double = interpolate(frac, HR_IF_TABLE)

/** Assumed IF for the duration fallback (easy aerobic). */
private const val ASSUMED_DURATION_IF = 0.65

/**
 * Speed stream (m/s): velocitySmooth when it has > 1 sample, else distance/time deltas (first sample 0,
 * negatives clamped to 0), else empty. Mirrors packages/core/src/science/load.ts#deriveSpeedStream.
 */
fun deriveSpeedStream(streams: ActivityStreams): List<Double> {
    val v = streams.velocitySmooth
    if (v != null && v.size > 1) return v
    val distance = streams.distance
    val time = streams.time?.takeIf { distance != null && it.size == distance.size && it.size > 1 }
    if (distance != null && time != null) {
        return List(distance.size) { i ->
            if (i == 0) {
                0.0
            } else {
                val dt = time[i] - time[i - 1]
                if (dt > 0) max(0.0, (distance[i] - distance[i - 1]) / dt) else 0.0
            }
        }
    }
    return emptyList()
}

/**
 * Grade stream as a FRACTION: gradeSmooth/100 when its length matches, else altitude/distance deltas clamped
 * to +/-0.45, else zeros. Mirrors packages/core/src/science/load.ts#deriveGradeStream.
 */
fun deriveGradeStream(streams: ActivityStreams, length: Int): List<Double> {
    val gs = streams.gradeSmooth
    if (gs != null && gs.size == length) return gs.map { it / 100 }
    val altitude = streams.altitude?.takeIf { it.size == length }
    val distance = streams.distance?.takeIf { it.size == length }
    if (altitude != null && distance != null) {
        return List(length) { i ->
            if (i == 0) {
                0.0
            } else {
                val dd = distance[i] - distance[i - 1]
                if (dd > 0) clamp((altitude[i] - altitude[i - 1]) / dd, -0.45, 0.45) else 0.0
            }
        }
    }
    return List(length) { 0.0 }
}

/**
 * Normalized Graded Pace: GAP (Minetti) -> 30 s time-window rolling mean -> dt-weighted 4th-power norm.
 * Uses ALL samples (no moving mask). Null when fewer than 2 speed samples. Missing/misaligned time uses the
 * sample index as seconds. Mirrors packages/core/src/science/load.ts#computeNgp.
 */
fun computeNgp(streams: ActivityStreams): NgpResult? {
    val speeds = deriveSpeedStream(streams)
    if (speeds.size < 2) return null
    val times = timesOrIndex(streams.time, speeds.size)
    val grades = deriveGradeStream(streams, speeds.size)
    val gap = speeds.mapIndexed { i, s -> s * gradeAdjustFactor(grades.getOrElse(i) { 0.0 }) }
    val rolled = rollingMeanByTime(gap, times, 30.0)
    val dts = timeDeltas(times)
    val ngp = fourthPowerNorm(rolled, dts)
    var weighted = 0.0
    var total = 0.0
    for (i in gap.indices) {
        weighted += gap[i] * dts[i]
        total += dts[i]
    }
    return NgpResult(ngpSpeedMps = ngp, meanGapSpeedMps = if (total > 0) weighted / total else 0.0)
}

/** hrTSS integrating IF^2 over moving time (moving mask, else scaled by moving/elapsed). Mirrors load.ts#hrTssFromStream. */
private fun hrTssFromStream(
    hr: List<Double>,
    time: List<Double>?,
    lthr: Double,
    moving: List<Boolean>?,
    movingTimeSec: Double,
    elapsedTimeSec: Double
): Double {
    val dts = timeDeltas(timesOrIndex(time, hr.size))
    val hasMoving = moving != null && moving.size == hr.size
    var acc = 0.0
    for (i in hr.indices) {
        if (hasMoving && !moving[i]) continue
        val intensity = ifFromHrFraction(hr[i] / lthr)
        acc += intensity * intensity * dts[i]
    }
    var tss = 100 * acc / SECONDS_PER_HOUR
    if (!hasMoving && elapsedTimeSec > movingTimeSec && elapsedTimeSec > 0) tss *= movingTimeSec / elapsedTimeSec
    return tss
}

/**
 * Banister TRIMP from average HR and max/resting HR references (HRR clamped to [0, 1], duration = movingTime).
 * Female weighting for Sex.FEMALE, otherwise the male weighting. Null without avg HR / max HR or when
 * max <= resting. Mirrors packages/core/src/science/load.ts#banisterTrimp.
 */
fun banisterTrimp(activity: Activity, profile: AthleteProfile): Double? {
    val avgHr = activity.averageHeartrate
    val maxHr = resolveMaxHr(profile)
    if (!avgHr.truthy() || !maxHr.truthy()) return null
    val rest = resolveRestingHr(profile)
    if (maxHr!! <= rest) return null
    val hrr = clamp((avgHr!! - rest) / (maxHr - rest), 0.0, 1.0)
    val durationMin = activity.movingTime / 60
    val factor = if (profile.sex == Sex.FEMALE) 0.86 * exp(1.67 * hrr) else 0.64 * exp(1.92 * hrr)
    return durationMin * hrr * factor
}

/**
 * Activity load via the best available method: rTSS from streams (NGP) -> rTSS from average speed -> hrTSS
 * from the HR stream -> hrTSS from average HR -> duration (IF 0.65). Pace methods are skipped for manual or
 * treadmill activities. Duration is movingTime; movingTime <= 0 gives method NONE with tss 0.
 * Mirrors packages/core/src/science/load.ts#computeActivityLoad.
 */
@Suppress("ReturnCount") // One early return per method, kept 1:1 with the TS fallback chain.
fun computeActivityLoad(activity: Activity, profile: AthleteProfile): LoadResult {
    val durationSec = activity.movingTime
    val hours = durationSec / SECONDS_PER_HOUR
    val base = LoadResult(tss = 0.0, method = LoadMethod.NONE, durationSec = durationSec, distanceM = activity.distance)
    if (hours <= 0) return base

    val threshold = resolveThresholdSpeed(profile)
    val trustPace = !activity.manual && !activity.trainer
    val hasThreshold = threshold.truthy() && threshold!! > 0

    if (hasThreshold && trustPace && activity.streams != null) {
        val ngp = computeNgp(activity.streams)
        if (ngp != null && ngp.ngpSpeedMps > 0) {
            val intensity = ngp.ngpSpeedMps / threshold
            return base.copy(
                method = LoadMethod.RTSS,
                intensityFactor = intensity,
                ngpSpeedMps = ngp.ngpSpeedMps,
                meanGapSpeedMps = ngp.meanGapSpeedMps,
                averageSpeedMps = activity.averageSpeed,
                averageHr = activity.averageHeartrate,
                tss = intensity * intensity * hours * 100
            )
        }
    }

    val avgSpeed = activity.averageSpeed?.takeIf { it.truthy() && it > 0 }
    if (hasThreshold && trustPace && avgSpeed != null) {
        val intensity = avgSpeed / threshold
        return base.copy(
            method = LoadMethod.RTSS,
            intensityFactor = intensity,
            averageSpeedMps = avgSpeed,
            averageHr = activity.averageHeartrate,
            tss = intensity * intensity * hours * 100
        )
    }

    val lthr = resolveLthr(profile)
    if (lthr.truthy() && lthr!! > 0) {
        val hr = activity.streams?.heartrate
        if (hr != null && hr.size > 1) {
            val streams = activity.streams
            val tss = hrTssFromStream(hr, streams.time, lthr, streams.moving, activity.movingTime, activity.elapsedTime)
            return base.copy(method = LoadMethod.HRTSS, averageHr = mean(hr), tss = tss)
        }
        val avgHr = activity.averageHeartrate
        if (avgHr.truthy() && avgHr!! > 0) {
            val intensity = ifFromHrFraction(avgHr / lthr)
            return base.copy(
                method = LoadMethod.HRTSS,
                intensityFactor = intensity,
                averageHr = avgHr,
                tss = intensity * intensity * hours * 100
            )
        }
    }

    return base.copy(
        method = LoadMethod.DURATION,
        intensityFactor = ASSUMED_DURATION_IF,
        averageSpeedMps = activity.averageSpeed,
        tss = ASSUMED_DURATION_IF * ASSUMED_DURATION_IF * hours * 100
    )
}
