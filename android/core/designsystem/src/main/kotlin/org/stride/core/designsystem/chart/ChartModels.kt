package org.stride.core.designsystem.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * A small Canvas chart toolkit (MVP-014..016). Charts only draw what they are
 * given: every number comes from core:science / core:data, the toolkit maps
 * values to pixels and formats axis labels.
 */

/** One line. [values] are evenly spaced on the x axis; NaN is a gap. */
@Immutable
data class LineSeries(val name: String, val values: List<Double>, val color: Color, val legendValue: String? = null)

/** A shaded horizontal band, e.g. the ACWR 0.8–1.3 sweet spot. */
@Immutable
data class HorizontalBand(val from: Double, val to: Double, val color: Color, val label: String? = null)

/** A dashed horizontal reference line, e.g. ACWR 1.5 or the 80 % easy target. */
@Immutable
data class ThresholdLine(val value: Double, val color: Color, val label: String? = null)

/** An x-axis label at a data index. */
@Immutable
data class AxisLabel(val index: Int, val text: String)

/** A highlighted point on the first series (e.g. an ACWR spike above 1.5). */
@Immutable
data class ChartMarker(val index: Int, val color: Color)

/** One bar split into stacked segments (bottom first). */
@Immutable
data class StackedBar(val label: String, val values: List<Double>)

/** One row of a horizontal zone-bar chart. [fraction] is 0..1 of the widest bar. */
@Immutable
data class ZoneBarRow(val label: String, val fraction: Float, val valueText: String, val color: Color)

/** A legend entry. */
@Immutable
data class LegendItem(val name: String, val color: Color, val value: String? = null)

/** Y-axis range plus "nice" tick values. */
data class AxisScale(val min: Double, val max: Double, val ticks: List<Double>) {
    val span: Double get() = (max - min).takeIf { it > 0 } ?: 1.0

    /** 0 at [min], 1 at [max]. */
    fun fraction(value: Double): Float = ((value - min) / span).toFloat()
}

object ChartMath {
    /**
     * A tidy y scale covering every finite value plus [include] (band edges,
     * thresholds, zero), rounded out to "nice" ticks (1, 2, 2.5, 5 × 10ⁿ).
     */
    fun scale(values: Iterable<Double>, include: Iterable<Double> = emptyList(), targetTicks: Int = 4): AxisScale {
        val finite = (values + include).filter { it.isFinite() }
        if (finite.isEmpty()) return AxisScale(0.0, 1.0, listOf(0.0, 1.0))
        var lo = finite.min()
        var hi = finite.max()
        if (lo == hi) {
            val pad = if (lo == 0.0) 1.0 else abs(lo) * 0.1
            lo -= pad
            hi += pad
        }
        val step = niceStep((hi - lo) / targetTicks.coerceAtLeast(1))
        val min = floor(lo / step) * step
        val max = ceil(hi / step) * step
        val count = ((max - min) / step).toInt()
        val ticks = (0..count).map { min + it * step }
        return AxisScale(min, max, ticks)
    }

    internal fun niceStep(raw: Double): Double {
        if (raw <= 0 || !raw.isFinite()) return 1.0
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val f = raw / base
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 2.5 -> 2.5
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    /** Number of source points per drawn point so at most [maxPoints] are drawn. */
    fun downsampleStride(size: Int, maxPoints: Int): Int = if (size <= maxPoints ||
        maxPoints <= 0
    ) {
        1
    } else {
        ceil(size.toDouble() / maxPoints).toInt()
    }

    /**
     * Bucket-averages [values] down to at most [maxPoints] points for drawing,
     * ignoring NaN samples; an all-NaN bucket stays NaN (a gap). Bucket `j`
     * starts at source index `j * downsampleStride(size, maxPoints)`.
     */
    fun downsample(values: DoubleArray, maxPoints: Int): List<Double> {
        val stride = downsampleStride(values.size, maxPoints)
        if (stride == 1) return values.toList()
        return (values.indices step stride).map { start ->
            var sum = 0.0
            var n = 0
            for (i in start until minOf(start + stride, values.size)) {
                val v = values[i]
                if (!v.isNaN()) {
                    sum += v
                    n++
                }
            }
            if (n == 0) Double.NaN else sum / n
        }
    }

    /** Default tick label: integers without decimals, otherwise one or two decimals. */
    fun defaultLabel(value: Double): String = when {
        abs(value - Math.round(value)) < 1e-9 -> Math.round(value).toString()
        abs(value * 10 - Math.round(value * 10)) < 1e-9 -> "%.1f".format(Locale.ROOT, value)
        else -> "%.2f".format(Locale.ROOT, value)
    }
}
