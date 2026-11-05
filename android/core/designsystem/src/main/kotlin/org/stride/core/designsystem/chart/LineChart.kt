package org.stride.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

/**
 * Multi-series line chart with an optional shaded [band], dashed [thresholds],
 * a zero line, y-axis value labels, x-axis (date) labels, point [markers] and a
 * legend. [contentDescription] is the screen-reader summary of the whole chart.
 * [invertY] draws larger values lower (pace: faster is higher).
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    xLabels: List<AxisLabel> = emptyList(),
    band: HorizontalBand? = null,
    thresholds: List<ThresholdLine> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    zeroLine: Boolean = false,
    invertY: Boolean = false,
    showLegend: Boolean = true,
    height: Dp = 200.dp,
    yLabel: (Double) -> String = ChartMath::defaultLabel
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val scale = remember(series, band, thresholds, zeroLine) {
        val extra = buildList {
            band?.let {
                add(it.from)
                add(it.to)
            }
            thresholds.forEach { add(it.value) }
            if (zeroLine) add(0.0)
        }
        ChartMath.scale(series.flatMap { it.values }, extra)
    }
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val tickLabels = scale.ticks.map { yLabel(it) }
            val gutterLeft = tickLabels.maxOf { textMeasurer.measure(it, labelStyle).size.width } + 8.dp.toPx()
            val gutterBottom = if (xLabels.isEmpty()) 4.dp.toPx() else textMeasurer.measure("0", labelStyle).size.height + 6.dp.toPx()
            val plot = PlotArea(gutterLeft, 6.dp.toPx(), size.width - gutterLeft - 4.dp.toPx(), size.height - gutterBottom - 6.dp.toPx())
            fun y(v: Double): Float {
                val f = scale.fraction(v)
                return if (invertY) plot.top + f * plot.height else plot.bottom - f * plot.height
            }
            val count = series.maxOfOrNull { it.values.size } ?: 0
            fun x(i: Int): Float = if (count <= 1) plot.left + plot.width / 2 else plot.left + plot.width * i / (count - 1)

            band?.let {
                val top = minOf(y(it.from), y(it.to))
                val bottom = maxOf(y(it.from), y(it.to))
                drawRect(it.color.copy(alpha = 0.14f), Offset(plot.left, top), Size(plot.width, bottom - top))
                it.label?.let { text ->
                    drawLabel(
                        textMeasurer,
                        text,
                        labelStyle.copy(color = it.color),
                        plot.left + 4.dp.toPx(),
                        top + 2.dp.toPx()
                    )
                }
            }
            scale.ticks.forEachIndexed { i, tick ->
                val ty = y(tick)
                drawLine(gridColor, Offset(plot.left, ty), Offset(plot.right, ty), strokeWidth = 1f)
                val layout = textMeasurer.measure(tickLabels[i], labelStyle)
                drawText(layout, topLeft = Offset(gutterLeft - layout.size.width - 6.dp.toPx(), ty - layout.size.height / 2f))
            }
            if (zeroLine && scale.min < 0 && scale.max > 0) {
                drawLine(zeroColor, Offset(plot.left, y(0.0)), Offset(plot.right, y(0.0)), strokeWidth = 1.5.dp.toPx())
            }
            thresholds.forEach { t ->
                val ty = y(t.value)
                drawLine(t.color, Offset(plot.left, ty), Offset(plot.right, ty), strokeWidth = 1.5.dp.toPx(), pathEffect = Dash)
                t.label?.let { text ->
                    val layout = textMeasurer.measure(text, labelStyle.copy(color = t.color))
                    drawText(layout, topLeft = Offset(plot.right - layout.size.width - 2.dp.toPx(), ty - layout.size.height - 1.dp.toPx()))
                }
            }
            series.forEach { s -> drawSeries(s, ::x, ::y, 2.dp.toPx()) }
            series.firstOrNull()?.let { first ->
                markers.forEach { m ->
                    val v = first.values.getOrNull(m.index)
                    if (v != null && v.isFinite()) drawCircle(m.color, radius = 4.dp.toPx(), center = Offset(x(m.index), y(v)))
                }
            }
            xLabels.forEach { label ->
                val layout = textMeasurer.measure(label.text, labelStyle)
                val lx = (x(label.index) - layout.size.width / 2f).coerceIn(plot.left, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(lx, plot.bottom + 4.dp.toPx()))
            }
        }
        if (showLegend && (series.size > 1 || series.any { it.legendValue != null })) {
            ChartLegend(items = series.map { LegendItem(it.name, it.color, it.legendValue) })
        }
    }
}

internal data class PlotArea(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private fun DrawScope.drawSeries(series: LineSeries, x: (Int) -> Float, y: (Double) -> Float, strokePx: Float) {
    val path = Path()
    var penDown = false
    var lonePoint: Offset? = null
    series.values.forEachIndexed { i, v ->
        if (!v.isFinite()) {
            penDown = false
            return@forEachIndexed
        }
        val p = Offset(x(i), y(v))
        if (penDown) {
            path.lineTo(p.x, p.y)
            lonePoint = null
        } else {
            path.moveTo(p.x, p.y)
            penDown = true
            lonePoint = p
        }
    }
    drawPath(path, series.color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    lonePoint?.let { drawCircle(series.color, radius = strokePx, center = it) }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, style: TextStyle, x: Float, y: Float) {
    drawText(measurer.measure(text, style), topLeft = Offset(x, y))
}

/** Tiny trend line without axes, e.g. resting HR over the last weeks. */
@Composable
fun Sparkline(values: List<Double>, color: Color, contentDescription: String, modifier: Modifier = Modifier) {
    val scale = remember(values) {
        val finite = values.filter { it.isFinite() }
        AxisScale(finite.minOrNull() ?: 0.0, finite.maxOrNull() ?: 1.0, emptyList())
    }
    Canvas(
        modifier = modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { this.contentDescription = contentDescription }
    ) {
        val stroke = 2.dp.toPx()
        val plot = PlotArea(stroke, stroke, size.width - 2 * stroke, size.height - 2 * stroke)
        val n = values.size
        drawSeries(
            LineSeries("", values, color),
            x = { i -> if (n <= 1) plot.left + plot.width / 2 else plot.left + plot.width * i / (n - 1) },
            y = { v -> plot.bottom - scale.fraction(v) * plot.height },
            strokePx = stroke
        )
    }
}
