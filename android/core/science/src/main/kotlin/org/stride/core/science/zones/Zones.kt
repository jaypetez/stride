package org.stride.core.science.zones

import org.stride.core.science.anchors.resolveLthr
import org.stride.core.science.anchors.resolveMaxHr
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.load.deriveGradeStream
import org.stride.core.science.load.deriveSpeedStream
import org.stride.core.science.model.Activity
import org.stride.core.science.model.ActivityStreams
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.IntensityLabel
import org.stride.core.science.model.PaceZone
import org.stride.core.science.model.ZoneDistribution
import org.stride.core.science.model.Zones
import org.stride.core.science.util.gradeAdjustFactor
import org.stride.core.science.util.jsRound
import org.stride.core.science.util.timeDeltas
import org.stride.core.science.util.timesOrIndex
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.truthy

/* Mirrors packages/core/src/science/zones.ts. */

private data class HrBound(val zone: Int, val name: String, val lo: Double)

/** HR zone lower bounds as fractions of LTHR (Friel, 5 zones). */
private val HR_ZONE_FRACTIONS = listOf(
    HrBound(1, "Recovery", 0.0),
    HrBound(2, "Aerobic", 0.85),
    HrBound(3, "Tempo", 0.9),
    HrBound(4, "Threshold", 0.95),
    HrBound(5, "VO2max", 1.0)
)

/**
 * Five HR zones from LTHR; bounds are Math.round(fraction x LTHR). The top of zone 5 is [maxHr] when it is
 * above LTHR, else 1.15 x LTHR. Mirrors packages/core/src/science/zones.ts#computeHrZones.
 */
fun computeHrZones(lthr: Double, maxHr: Double? = null): List<HrZone> {
    val top = if (maxHr.truthy() && maxHr!! > lthr) maxHr else lthr * 1.15
    return HR_ZONE_FRACTIONS.mapIndexed { i, z ->
        val next = HR_ZONE_FRACTIONS.getOrNull(i + 1)
        HrZone(
            zone = z.zone,
            name = z.name,
            minBpm = jsRound(z.lo * lthr),
            maxBpm = if (next != null) jsRound(next.lo * lthr) else jsRound(top)
        )
    }
}

private data class PaceBound(val zone: Int, val label: IntensityLabel, val name: String, val lo: Double)

/** Pace zone lower bounds as fractions of threshold speed (Daniels intensities). */
private val PACE_ZONE_FRACTIONS = listOf(
    PaceBound(1, IntensityLabel.E, "Easy", 0.0),
    PaceBound(2, IntensityLabel.M, "Marathon", 0.88),
    PaceBound(3, IntensityLabel.T, "Threshold", 0.94),
    PaceBound(4, IntensityLabel.I, "Interval", 1.03),
    PaceBound(5, IntensityLabel.R, "Repetition", 1.12)
)

/** Five pace zones as speed bounds (m/s, 3 dp); zone 5 tops out at 2 x threshold. Mirrors zones.ts#computePaceZones. */
fun computePaceZones(thresholdSpeedMps: Double): List<PaceZone> = PACE_ZONE_FRACTIONS.mapIndexed { i, z ->
    val nextLo = PACE_ZONE_FRACTIONS.getOrNull(i + 1)?.lo ?: 2.0
    PaceZone(
        zone = z.zone,
        label = z.label,
        name = z.name,
        minSpeedMps = toFixed(z.lo * thresholdSpeedMps, 3),
        maxSpeedMps = toFixed(nextLo * thresholdSpeedMps, 3)
    )
}

/** HR and pace zones from the profile's resolved anchors (empty lists when unknown). Mirrors zones.ts#computeZones. */
fun computeZones(profile: AthleteProfile): Zones {
    val lthr = resolveLthr(profile)
    val threshold = resolveThresholdSpeed(profile)
    return Zones(
        hr = if (lthr.truthy()) computeHrZones(lthr!!, resolveMaxHr(profile)) else emptyList(),
        pace = if (threshold.truthy()) computePaceZones(threshold!!) else emptyList()
    )
}

/**
 * Seconds per HR zone, keyed by zone number as a string (unrounded). A sample belongs to the first zone with
 * minBpm <= bpm < maxBpm, else to the LAST zone. Non-moving samples are skipped when [moving] matches [hr].
 * Mirrors packages/core/src/science/zones.ts#timeInHrZones.
 */
fun timeInHrZones(hr: List<Double>, time: List<Double>?, zones: List<HrZone>, moving: List<Boolean>? = null): Map<String, Double> {
    val dts = timeDeltas(timesOrIndex(time, hr.size))
    val hasMoving = moving != null && moving.size == hr.size
    val out = LinkedHashMap<String, Double>()
    for (z in zones) out[z.zone.toString()] = 0.0
    for (i in hr.indices) {
        if (hasMoving && !moving[i]) continue
        val bpm = hr[i]
        val zone = zones.firstOrNull { bpm >= it.minBpm && bpm < it.maxBpm } ?: zones.lastOrNull()
        if (zone != null) out[zone.zone.toString()] = out.getValue(zone.zone.toString()) + dts[i]
    }
    return out
}

/** Easy/moderate/hard seconds for one activity. */
data class PolarizedSeconds(val easy: Double, val moderate: Double, val hard: Double)

private class Buckets {
    var easy = 0.0
    var moderate = 0.0
    var hard = 0.0

    fun add(fraction: Double, seconds: Double) {
        when {
            fraction < 0.9 -> easy += seconds
            fraction < 1.0 -> moderate += seconds
            else -> hard += seconds
        }
    }

    fun result() = PolarizedSeconds(easy, moderate, hard)
}

/**
 * Easy (< 0.9) / moderate (< 1.0) / hard seconds for one activity: from the HR stream vs LTHR when possible,
 * else the GAP speed stream vs threshold, else the whole movingTime by average HR, average speed, or easy.
 * Mirrors packages/core/src/science/zones.ts#activityZoneSeconds.
 */
fun activityZoneSeconds(activity: Activity, profile: AthleteProfile): PolarizedSeconds {
    val acc = Buckets()
    val lthr = resolveLthr(profile)
    val threshold = resolveThresholdSpeed(profile)
    val s = activity.streams
    val hr = s?.heartrate
    val avgHr = activity.averageHeartrate
    val avgSpeed = activity.averageSpeed
    val t = activity.movingTime
    when {
        lthr.truthy() && hr != null && hr.size > 1 -> addHrSamples(acc, s, hr, lthr!!)
        threshold.truthy() && s != null && deriveSpeedStream(s).size > 1 -> addSpeedSamples(acc, s, threshold!!)
        lthr.truthy() && avgHr.truthy() -> acc.add(avgHr!! / lthr!!, t)
        threshold.truthy() && avgSpeed.truthy() -> acc.add(avgSpeed!! / threshold!!, t)
        else -> acc.easy += t
    }
    return acc.result()
}

private fun addHrSamples(acc: Buckets, s: ActivityStreams, hr: List<Double>, lthr: Double) {
    val dts = timeDeltas(timesOrIndex(s.time, hr.size))
    val moving = s.moving?.takeIf { it.size == hr.size }
    for (i in hr.indices) {
        if (moving == null || moving[i]) acc.add(hr[i] / lthr, dts[i])
    }
}

private fun addSpeedSamples(acc: Buckets, s: ActivityStreams, threshold: Double) {
    val speeds = deriveSpeedStream(s)
    val dts = timeDeltas(timesOrIndex(s.time, speeds.size))
    val grades = deriveGradeStream(s, speeds.size)
    val moving = s.moving?.takeIf { it.size == speeds.size }
    for (i in speeds.indices) {
        if (moving == null || moving[i]) acc.add(speeds[i] * gradeAdjustFactor(grades.getOrElse(i) { 0.0 }) / threshold, dts[i])
    }
}

/**
 * Polarized distribution over [activities]: seconds rounded with Math.round, percentages to 1 dp (0 when no
 * time). Mirrors packages/core/src/science/zones.ts#zoneDistribution.
 */
fun zoneDistribution(activities: List<Activity>, profile: AthleteProfile): ZoneDistribution {
    var easy = 0.0
    var moderate = 0.0
    var hard = 0.0
    for (a in activities) {
        val z = activityZoneSeconds(a, profile)
        easy += z.easy
        moderate += z.moderate
        hard += z.hard
    }
    val total = easy + moderate + hard
    fun pct(x: Double) = if (total > 0) toFixed(100 * x / total, 1) else 0.0
    return ZoneDistribution(
        easySec = jsRound(easy),
        moderateSec = jsRound(moderate),
        hardSec = jsRound(hard),
        easyPct = pct(easy),
        moderatePct = pct(moderate),
        hardPct = pct(hard)
    )
}
