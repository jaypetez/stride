package org.stride.core.data.insights

/*
 * Small derived values the screens show next to the computed insights. They
 * live here (not in the UI) so every number on screen is computed in tested code.
 */

/** ACWR sweet spot and spike threshold (GOAL.md §7; the flag logic is core:science `buildAcwrSeries`). */
const val ACWR_SWEET_SPOT_MIN = 0.8
const val ACWR_SWEET_SPOT_MAX = 1.3
const val ACWR_SPIKE = 1.5

/** Seiler's polarized target: about 80 % of running time easy. */
const val POLARIZED_EASY_TARGET_PCT = 80.0

/** This week minus last week, per field. */
data class VolumeDelta(val distanceM: Double, val durationSec: Double, val load: Double, val runs: Int)

fun WeeklyVolume.deltaFrom(previous: WeeklyVolume): VolumeDelta = VolumeDelta(
    distanceM = distanceM - previous.distanceM,
    durationSec = durationSec - previous.durationSec,
    load = load - previous.load,
    runs = runs - previous.runs
)

/** Share of each HR zone in [zoneSeconds] (0..1, sums to 1), or empty when there is no time. */
fun zoneShares(zoneSeconds: List<Double>): List<Double> {
    val total = zoneSeconds.sum()
    return if (total <= 0) emptyList() else zoneSeconds.map { it / total }
}

/** Pace (seconds per km) for a speed sample; NaN for gaps and standing still. */
fun paceSecPerKm(speedMps: Double): Double = if (speedMps.isNaN() || speedMps < MIN_MOVING_MPS) Double.NaN else 1000.0 / speedMps

/** Below this a sample counts as standing still (a 33 min/km "pace" would wreck the chart scale). */
const val MIN_MOVING_MPS = 0.5
