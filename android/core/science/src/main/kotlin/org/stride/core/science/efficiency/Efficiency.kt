package org.stride.core.science.efficiency

import org.stride.core.science.load.computeNgp
import org.stride.core.science.load.deriveGradeStream
import org.stride.core.science.load.deriveSpeedStream
import org.stride.core.science.model.Activity
import org.stride.core.science.util.gradeAdjustFactor
import org.stride.core.science.util.mean
import org.stride.core.science.util.timeDeltas
import org.stride.core.science.util.timesOrIndex
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.truthy

/**
 * Efficiency Factor = mean grade-adjusted speed (m/min) / average HR, rounded to 2 dp. Average HR prefers
 * activity.averageHeartrate over the stream mean; speed prefers computeNgp(streams).meanGapSpeedMps over
 * averageSpeed. Null when either is missing or non-positive. Mirrors packages/core/src/science/efficiency.ts#efficiencyFactor.
 */
fun efficiencyFactor(activity: Activity): Double? {
    val s = activity.streams
    val avgHr = activity.averageHeartrate ?: s?.heartrate?.let { mean(it) }
    if (!avgHr.truthy() || avgHr!! <= 0) return null
    val speedMps = s?.let { computeNgp(it)?.meanGapSpeedMps } ?: activity.averageSpeed
    if (!speedMps.truthy() || speedMps!! <= 0) return null
    return toFixed(speedMps * 60 / avgHr, 2)
}

/**
 * Aerobic decoupling (Pa:HR) in percent, 1 dp: efficiency loss from the first to the second half, split at
 * the time midpoint (times[i] < mid is the first half). Needs >= 4 HR samples and a speed stream of the same
 * length; null otherwise. Mirrors packages/core/src/science/efficiency.ts#aerobicDecoupling.
 */
@Suppress("ReturnCount") // Guard clauses mirror the TS 1:1.
fun aerobicDecoupling(activity: Activity): Double? {
    val s = activity.streams ?: return null
    val hr = s.heartrate
    if (hr == null || hr.size < 4) return null
    val speeds = deriveSpeedStream(s)
    if (speeds.size != hr.size) return null
    val grades = deriveGradeStream(s, speeds.size)
    val times = timesOrIndex(s.time, speeds.size)
    val dts = timeDeltas(times)
    val midTime = times.first() + (times.last() - times.first()) / 2
    var gap1 = 0.0
    var hr1 = 0.0
    var gap2 = 0.0
    var hr2 = 0.0
    for (i in speeds.indices) {
        val gap = speeds[i] * gradeAdjustFactor(grades.getOrElse(i) { 0.0 })
        if (times[i] < midTime) {
            gap1 += gap * dts[i]
            hr1 += hr[i] * dts[i]
        } else {
            gap2 += gap * dts[i]
            hr2 += hr[i] * dts[i]
        }
    }
    if (hr1 <= 0 || hr2 <= 0) return null
    val ef1 = gap1 / hr1
    val ef2 = gap2 / hr2
    if (ef1 <= 0) return null
    return toFixed((ef1 - ef2) / ef1 * 100, 1)
}
