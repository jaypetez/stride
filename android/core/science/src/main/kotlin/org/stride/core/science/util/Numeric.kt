package org.stride.core.science.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/* Shared numeric helpers. Mirrors packages/core/src/science/util.ts. */

internal const val SECONDS_PER_HOUR = 3600.0
internal const val METERS_PER_KM = 1000.0

internal fun clamp(x: Double, lo: Double, hi: Double): Double = min(hi, max(lo, x))

/** Arithmetic mean; 0 for an empty list. Mirrors util.ts#mean. */
internal fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

/** Speed (m/s) to pace (s/km); +Infinity at 0. Mirrors util.ts#mpsToSecPerKm. */
fun mpsToSecPerKm(mps: Double): Double = if (mps > 0) METERS_PER_KM / mps else Double.POSITIVE_INFINITY

/** Pace (s/km) to speed (m/s); 0 when pace <= 0. Mirrors util.ts#secPerKmToMps. */
fun secPerKmToMps(secPerKm: Double): Double = if (secPerKm > 0) METERS_PER_KM / secPerKm else 0.0

/**
 * Minetti (2002) energy cost of running (J/kg/m) at a grade given as a fraction, clamped to [-0.45, 0.45].
 * Mirrors packages/core/src/science/util.ts#minettiCost.
 */
fun minettiCost(gradeFraction: Double): Double {
    val i = clamp(gradeFraction, -0.45, 0.45)
    return 155.4 * i.pow(5) - 30.4 * i.pow(4) - 43.3 * i.pow(3) + 46.3 * i.pow(2) + 19.5 * i + 3.6
}

private val FLAT_COST = minettiCost(0.0)

/**
 * Grade-adjustment factor: gap_speed = raw_speed * gradeAdjustFactor(grade). Grade is a fraction.
 * Mirrors packages/core/src/science/util.ts#gradeAdjustFactor.
 */
fun gradeAdjustFactor(gradeFraction: Double): Double = minettiCost(gradeFraction) / FLAT_COST

/**
 * Time-windowed rolling mean: each output averages (by SAMPLE COUNT) the values whose timestamps lie
 * within [windowSec] before it. Mirrors util.ts#rollingMeanByTime.
 */
internal fun rollingMeanByTime(values: List<Double>, times: List<Double>, windowSec: Double): List<Double> {
    val n = values.size
    val out = DoubleArray(n)
    var start = 0
    var windowSum = 0.0
    for (end in 0 until n) {
        windowSum += values[end]
        while (times[end] - times[start] > windowSec && start < end) {
            windowSum -= values[start]
            start++
        }
        out[end] = windowSum / (end - start + 1)
    }
    return out.asList()
}

/** dt-weighted (mean(x^4))^(1/4). Mirrors util.ts#fourthPowerNorm. */
internal fun fourthPowerNorm(values: List<Double>, dts: List<Double>): Double {
    var weighted = 0.0
    var totalDt = 0.0
    for (i in values.indices) {
        val dt = dts.getOrElse(i) { 0.0 }
        weighted += values[i].pow(4) * dt
        totalDt += dt
    }
    if (totalDt == 0.0) return 0.0
    return (weighted / totalDt).pow(0.25)
}

/** Per-sample dt; the last sample repeats the previous dt (1 for a single sample). Mirrors util.ts#timeDeltas. */
internal fun timeDeltas(times: List<Double>): List<Double> {
    val n = times.size
    if (n == 0) return emptyList()
    val dts = DoubleArray(n)
    for (i in 0 until n - 1) dts[i] = max(0.0, times[i + 1] - times[i])
    dts[n - 1] = if (n > 1) dts[n - 2] else 1.0
    return dts.asList()
}

/** Linear interpolation over a sorted table, clamped at both ends. Mirrors util.ts#interpolate. */
@Suppress("ReturnCount")
internal fun interpolate(x: Double, table: List<Pair<Double, Double>>): Double {
    if (table.isEmpty()) return 0.0
    if (x <= table.first().first) return table.first().second
    val last = table.last()
    if (x >= last.first) return last.second
    for (i in 0 until table.size - 1) {
        val (x0, y0) = table[i]
        val (x1, y1) = table[i + 1]
        if (x in x0..x1) return y0 + (x - x0) / (x1 - x0) * (y1 - y0)
    }
    return last.second
}

/** Index-as-seconds fallback used whenever a time stream is missing or misaligned. */
internal fun timesOrIndex(time: List<Double>?, length: Int): List<Double> =
    if (time != null && time.size == length) time else List(length) { it.toDouble() }
