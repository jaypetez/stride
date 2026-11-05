package org.stride.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stacked vertical bars (bottom segment first), e.g. weekly easy / moderate /
 * hard time. [maxValue] fixes the y range (100 for percentages); [target] draws
 * a dashed reference line such as the 80 % easy target.
 */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    segmentColors: List<Color>,
    segmentNames: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    maxValue: Double? = null,
    target: ThresholdLine? = null,
    labelEvery: Int = 1,
    height: Dp = 180.dp,
    yLabel: (Double) -> String = ChartMath::defaultLabel
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = maxValue ?: (bars.maxOfOrNull { b -> b.values.sum() } ?: 1.0)
    val scale = if (maxValue != null) {
        AxisScale(0.0, maxValue, ChartMath.scale(listOf(0.0, maxValue)).ticks.filter { it <= maxValue })
    } else {
        ChartMath.scale(listOf(0.0, top))
    }
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val tickLabels = scale.ticks.map(yLabel)
            val gutterLeft = (tickLabels.maxOfOrNull { textMeasurer.measure(it, labelStyle).size.width } ?: 0) + 8.dp.toPx()
            val gutterBottom = textMeasurer.measure("0", labelStyle).size.height + 6.dp.toPx()
            val plot = PlotArea(gutterLeft, 6.dp.toPx(), size.width - gutterLeft, size.height - gutterBottom - 6.dp.toPx())
            fun y(v: Double) = plot.bottom - scale.fraction(v) * plot.height
            scale.ticks.forEachIndexed { i, tick ->
                drawLine(gridColor, Offset(plot.left, y(tick)), Offset(plot.right, y(tick)), strokeWidth = 1f)
                val layout = textMeasurer.measure(tickLabels[i], labelStyle)
                drawText(layout, topLeft = Offset(gutterLeft - layout.size.width - 6.dp.toPx(), y(tick) - layout.size.height / 2f))
            }
            if (bars.isNotEmpty()) {
                val slot = plot.width / bars.size
                val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
                bars.forEachIndexed { i, bar ->
                    val left = plot.left + slot * i + (slot - barWidth) / 2
                    var base = 0.0
                    bar.values.forEachIndexed { s, v ->
                        if (v > 0) {
                            val yTop = y(base + v)
                            drawRect(segmentColors[s % segmentColors.size], Offset(left, yTop), Size(barWidth, y(base) - yTop))
                            base += v
                        }
                    }
                    if (i % labelEvery.coerceAtLeast(1) == 0) {
                        val layout = textMeasurer.measure(bar.label, labelStyle)
                        val lx = (left + barWidth / 2 - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                        drawText(layout, topLeft = Offset(lx, plot.bottom + 4.dp.toPx()))
                    }
                }
            }
            target?.let { t ->
                val ty = y(t.value)
                drawLine(
                    t.color,
                    Offset(plot.left, ty),
                    Offset(plot.right, ty),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
                t.label?.let { text ->
                    val layout = textMeasurer.measure(text, labelStyle.copy(color = t.color))
                    drawText(layout, topLeft = Offset(plot.right - layout.size.width, ty - layout.size.height - 2.dp.toPx()))
                }
            }
        }
        ChartLegend(items = segmentNames.mapIndexed { i, name -> LegendItem(name, segmentColors[i % segmentColors.size]) })
    }
}

/** Horizontal bars, one per zone (label · bar · value), e.g. time in HR zones. */
@Composable
fun ZoneBars(rows: List<ZoneBarRow>, contentDescription: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(40.dp),
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.fraction.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(row.color)
                    )
                }
                Text(
                    text = row.valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                    maxLines = 1
                )
            }
        }
    }
}

/** Colour swatches with names (and optional current values) under a chart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(item.color))
                Text(
                    text = if (item.value != null) "${item.name} ${item.value}" else item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
