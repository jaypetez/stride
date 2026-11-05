package org.stride.core.designsystem.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme

/** Sample charts shared by IDE previews and the Roborazzi chart tests. Values are illustrative only. */
object ChartShowcase {
    private val days = (0 until 42)
    private val ctl = days.map { 40 + it * 0.2 + 2 * sin(it / 6.0) }
    private val atl = days.map { 45 + 10 * sin(it * PI / 7) }
    private val tsb = ctl.zip(atl) { c, a -> c - a }
    private val acwr = days.map { 1.0 + 0.3 * sin(it * PI / 10) + if (it == 30) 0.35 else 0.0 }
    private val dates = listOf(AxisLabel(0, "21 Sep"), AxisLabel(14, "5 Oct"), AxisLabel(28, "19 Oct"), AxisLabel(41, "1 Nov"))

    @Composable
    fun Pmc() = Frame {
        val colors = StrideTheme.colors
        LineChart(
            series = listOf(
                LineSeries("Fitness", ctl, colors.fitness, "48"),
                LineSeries("Fatigue", atl, colors.fatigue, "52"),
                LineSeries("Form", tsb, colors.form, "−4")
            ),
            contentDescription = "Fitness, fatigue and form over 6 weeks",
            xLabels = dates,
            zeroLine = true
        )
    }

    @Composable
    fun Acwr() = Frame {
        val colors = StrideTheme.colors
        LineChart(
            series = listOf(LineSeries("ACWR", acwr, colors.fitness)),
            contentDescription = "Acute to chronic workload ratio",
            xLabels = dates,
            band = HorizontalBand(0.8, 1.3, colors.fresh, "0.8–1.3"),
            thresholds = listOf(ThresholdLine(1.5, colors.overreaching, "1.5")),
            markers = acwr.indices.filter { acwr[it] > 1.5 }.map { ChartMarker(it, colors.overreaching) },
            showLegend = false
        )
    }

    @Composable
    fun Pace() = Frame {
        val colors = StrideTheme.colors
        val values = (0 until 120).map { if (it in 50..54) Double.NaN else 320 + 15 * cos(it / 9.0) }
        LineChart(
            series = listOf(LineSeries("Pace", values, colors.fitness)),
            contentDescription = "Pace over time",
            xLabels = listOf(AxisLabel(0, "0:00"), AxisLabel(60, "25:00"), AxisLabel(119, "50:00")),
            invertY = true,
            showLegend = false,
            height = 140.dp,
            yLabel = { "%d:%02d".format(java.util.Locale.ROOT, it.toInt() / 60, it.toInt() % 60) }
        )
    }

    @Composable
    fun Zones() = Frame {
        val colors = StrideTheme.colors
        val split = listOf(StrideTheme.colors.zone2, colors.zone3, colors.zone5)
        StackedBarChart(
            bars = listOf(
                StackedBar("14 Sep", listOf(82.0, 10.0, 8.0)),
                StackedBar("21 Sep", listOf(74.0, 16.0, 10.0)),
                StackedBar("28 Sep", listOf(79.0, 12.0, 9.0)),
                StackedBar("5 Oct", listOf(85.0, 7.0, 8.0)),
                StackedBar("12 Oct", listOf(68.0, 20.0, 12.0)),
                StackedBar("19 Oct", listOf(80.0, 11.0, 9.0))
            ),
            segmentColors = split,
            segmentNames = listOf("Easy", "Moderate", "Hard"),
            contentDescription = "Weekly intensity distribution",
            maxValue = 100.0,
            target = ThresholdLine(80.0, colors.positive, "80 % easy"),
            yLabel = { "${it.toInt()}%" }
        )
        ZoneBars(
            rows = listOf(0.05f, 0.62f, 0.2f, 0.1f, 0.03f).mapIndexed { i, f ->
                ZoneBarRow("Z${i + 1}", f / 0.62f, "${(f * 100).toInt()} %", colors.zones[i])
            },
            contentDescription = "Time in heart-rate zones"
        )
        Sparkline(
            values = listOf(52.0, 51.0, 51.0, 50.0, 49.0, 50.0, 48.0),
            color = colors.positive,
            contentDescription = "Resting HR trend"
        )
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Surface {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@ThemePreviews
@Composable
private fun ChartsPreview() {
    StrideTheme {
        Column {
            ChartShowcase.Pmc()
            ChartShowcase.Acwr()
        }
    }
}
