package org.stride.core.science.vdot

import kotlin.math.exp
import kotlin.math.sqrt
import org.stride.core.science.model.TrainingSpeeds

/* Jack Daniels VDOT model (Daniels-Gilbert). Mirrors packages/core/src/science/vdot.ts. */

private const val A = 0.000104
private const val B = 0.182258
private const val C = -4.6

/** Fraction of VO2max sustainable for a race lasting [timeMin] minutes. Mirrors vdot.ts#percentVo2Max. */
fun percentVo2Max(timeMin: Double): Double = 0.8 + 0.1894393 * exp(-0.012778 * timeMin) + 0.2989558 * exp(-0.1932605 * timeMin)

/** Oxygen cost (ml/kg/min) at [vMetersPerMin]. Mirrors vdot.ts#vo2FromVelocity. */
fun vo2FromVelocity(vMetersPerMin: Double): Double = C + B * vMetersPerMin + A * vMetersPerMin * vMetersPerMin

/** VDOT from [distanceM] metres run in [timeSec] seconds; 0 for non-positive input. Mirrors vdot.ts#vdotFromEffort. */
fun vdotFromEffort(distanceM: Double, timeSec: Double): Double {
    if (distanceM <= 0 || timeSec <= 0) return 0.0
    val timeMin = timeSec / 60
    val vMetersPerMin = distanceM / timeMin
    return vo2FromVelocity(vMetersPerMin) / percentVo2Max(timeMin)
}

/** Velocity (m/min) costing [pct] of [vdot]. Mirrors vdot.ts#velocityAtPercentVo2. */
fun velocityAtPercentVo2(vdot: Double, pct: Double): Double {
    val target = pct * vdot
    val disc = B * B - 4 * A * (C - target)
    if (disc < 0) return 0.0
    return (-B + sqrt(disc)) / (2 * A)
}

/** Threshold speed (m/s) at 88% of VDOT, the rTSS anchor. Mirrors vdot.ts#thresholdSpeedFromVdot. */
fun thresholdSpeedFromVdot(vdot: Double): Double = velocityAtPercentVo2(vdot, 0.88) / 60

/** E/M/T/I/R training speeds (m/s) at 70/84/88/98/110% of VDOT. Mirrors vdot.ts#trainingSpeeds. */
fun trainingSpeeds(vdot: Double): TrainingSpeeds {
    fun at(pct: Double) = velocityAtPercentVo2(vdot, pct) / 60
    return TrainingSpeeds(easy = at(0.7), marathon = at(0.84), threshold = at(0.88), interval = at(0.98), repetition = at(1.1))
}

/**
 * Predicted race time (s) for [distanceM] at [vdot]: 60 bisection steps over [60 s, 6 h], returning the
 * bracket midpoint (so results are capped at 6 h). 0 for non-positive input. Mirrors vdot.ts#predictRaceTimeSec.
 */
fun predictRaceTimeSec(vdot: Double, distanceM: Double): Double {
    if (vdot <= 0 || distanceM <= 0) return 0.0
    var lo = 60.0
    var hi = 6 * 3600.0
    repeat(60) {
        val mid = (lo + hi) / 2
        if (vdotFromEffort(distanceM, mid) > vdot) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}
