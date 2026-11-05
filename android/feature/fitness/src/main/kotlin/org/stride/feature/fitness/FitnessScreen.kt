package org.stride.feature.fitness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.stride.core.data.insights.ACWR_SPIKE
import org.stride.core.data.insights.ACWR_SWEET_SPOT_MAX
import org.stride.core.data.insights.ACWR_SWEET_SPOT_MIN
import org.stride.core.data.insights.FitnessInsights
import org.stride.core.data.insights.POLARIZED_EASY_TARGET_PCT
import org.stride.core.data.insights.RacePrediction
import org.stride.core.data.insights.VdotPoint
import org.stride.core.data.insights.WeeklyZones
import org.stride.core.designsystem.chart.AxisLabel
import org.stride.core.designsystem.chart.ChartMarker
import org.stride.core.designsystem.chart.HorizontalBand
import org.stride.core.designsystem.chart.LineChart
import org.stride.core.designsystem.chart.LineSeries
import org.stride.core.designsystem.chart.StackedBar
import org.stride.core.designsystem.chart.StackedBarChart
import org.stride.core.designsystem.chart.ThresholdLine
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.DailyLoadPoint
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.PaceZone
import org.stride.core.ui.Format
import org.stride.core.ui.LocalUnitSystem

@Composable
fun FitnessRoute(modifier: Modifier = Modifier, viewModel: FitnessViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FitnessScreen(state = state, onSelectRange = viewModel::selectRange, modifier = modifier)
}

/** Fitness (MVP-016): PMC, ACWR, weekly intensity split, VDOT, race estimates and zones. */
@Composable
fun FitnessScreen(state: FitnessUiState, onSelectRange: (FitnessRange) -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Fitness") }) { padding ->
            val insights = state.insights
            when {
                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                state.isEmpty || insights == null -> EmptyState(
                    title = "No fitness data yet",
                    message = "Fitness, fatigue and form appear once a few runs have synced.",
                    modifier = Modifier.padding(padding)
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RangeChips(state.range, onSelectRange)
                    PmcCard(state.window, insights.rampRatePerWeek)
                    AcwrCard(state.window)
                    if (insights.weeklyZones.isNotEmpty()) ZonesCard(insights.weeklyZones)
                    if (insights.vdotTrend.isNotEmpty() || insights.predictions.isNotEmpty()) {
                        VdotCard(insights.vdotTrend, insights.currentVdot, insights.predictions)
                    }
                    ZoneTablesCard(insights)
                }
            }
        }
    }
}

@Composable
private fun RangeChips(selected: FitnessRange, onSelect: (FitnessRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitnessRange.entries.forEach { r ->
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text(r.label) })
        }
    }
}

@Composable
private fun PmcCard(window: List<DailyLoadPoint>, rampRatePerWeek: Double?, modifier: Modifier = Modifier) {
    val colors = StrideTheme.colors
    val latest = window.lastOrNull()
    StrideCard(
        modifier = modifier,
        title = "Fitness, fatigue & form",
        subtitle = rampRatePerWeek?.let { "Fitness ramp ${Format.signed(it, 1)} CTL/week" }
    ) {
        LineChart(
            series = listOf(
                LineSeries("Fitness (CTL)", window.map { it.ctl }, colors.fitness, latest?.let { Format.number(it.ctl, 0) }),
                LineSeries("Fatigue (ATL)", window.map { it.atl }, colors.fatigue, latest?.let { Format.number(it.atl, 0) }),
                LineSeries("Form (TSB)", window.map { it.tsb }, colors.form, latest?.let { Format.signed(it.tsb) })
            ),
            contentDescription = latest?.let {
                "Performance management chart over ${window.size} days. Latest fitness ${Format.number(it.ctl, 0)}, " +
                    "fatigue ${Format.number(it.atl, 0)}, form ${Format.signed(it.tsb)}."
            } ?: "Performance management chart, no data",
            xLabels = dateLabels(window.map { it.date }),
            zeroLine = true
        )
        Text(
            text = "Fitness is your 42-day training load, fatigue the 7-day load; form is yesterday's fitness minus fatigue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AcwrCard(window: List<DailyLoadPoint>, modifier: Modifier = Modifier) {
    val colors = StrideTheme.colors
    val values = window.map { it.acwr ?: Double.NaN }
    if (values.none { it.isFinite() }) return
    val spikes = values.indices.filter { values[it] > ACWR_SPIKE }
    val latest = values.lastOrNull { it.isFinite() }
    StrideCard(modifier = modifier, title = "Acute:chronic workload", subtitle = latest?.let { "Now ${Format.number(it, 2)}" }) {
        LineChart(
            series = listOf(LineSeries("ACWR", values, colors.fitness)),
            contentDescription = "Acute to chronic workload ratio over ${window.size} days" +
                (latest?.let { ", now ${Format.number(it, 2)}" } ?: "") +
                ", ${spikes.size} days above ${Format.number(ACWR_SPIKE, 1)}.",
            xLabels = dateLabels(window.map { it.date }),
            band = HorizontalBand(ACWR_SWEET_SPOT_MIN, ACWR_SWEET_SPOT_MAX, colors.fresh, "sweet spot"),
            thresholds = listOf(ThresholdLine(ACWR_SPIKE, colors.overreaching, "spike")),
            markers = spikes.map { ChartMarker(it, colors.overreaching) },
            showLegend = false,
            height = 160.dp
        )
        Text(
            text = "Last 7 days of load against the last 28. Between ${Format.number(ACWR_SWEET_SPOT_MIN, 1)} and " +
                "${Format.number(ACWR_SWEET_SPOT_MAX, 1)} is the usual sweet spot; above ${Format.number(ACWR_SPIKE, 1)} " +
                "(red dots) load rose quickly. One signal among several, not a prediction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ZonesCard(weeks: List<WeeklyZones>, modifier: Modifier = Modifier) {
    val colors = StrideTheme.colors
    val latest = weeks.last().distribution
    StrideCard(
        modifier = modifier,
        title = "Intensity distribution",
        subtitle = "Easy share this week ${Format.number(latest.easyPct, 0)} % · target ~${Format.number(POLARIZED_EASY_TARGET_PCT, 0)} %"
    ) {
        StackedBarChart(
            bars = weeks.map { w ->
                StackedBar(shortDate(w.weekStart), listOf(w.distribution.easyPct, w.distribution.moderatePct, w.distribution.hardPct))
            },
            segmentColors = listOf(colors.zone2, colors.zone3, colors.zone5),
            segmentNames = listOf("Easy", "Moderate", "Hard"),
            contentDescription = "Weekly easy, moderate and hard running time over ${weeks.size} weeks. " +
                "This week ${Format.number(latest.easyPct, 0)} percent easy, ${Format.number(latest.moderatePct, 0)} moderate, " +
                "${Format.number(latest.hardPct, 0)} hard; target 80 percent easy.",
            maxValue = 100.0,
            target = ThresholdLine(POLARIZED_EASY_TARGET_PCT, colors.positive, "80/20"),
            labelEvery = if (weeks.size > 6) 2 else 1,
            yLabel = { "${it.toInt()}%" }
        )
    }
}

@Composable
private fun VdotCard(trend: List<VdotPoint>, currentVdot: Double?, predictions: List<RacePrediction>, modifier: Modifier = Modifier) {
    StrideCard(
        modifier = modifier,
        title = "VDOT & race estimates",
        subtitle = currentVdot?.let { "Current VDOT ${Format.number(it, 1)}" }
    ) {
        if (trend.size > 1) {
            LineChart(
                series = listOf(LineSeries("VDOT", trend.map { it.vdot }, StrideTheme.colors.fitness)),
                contentDescription = "VDOT by week, from ${Format.number(trend.first().vdot, 1)} to ${Format.number(trend.last().vdot, 1)}",
                xLabels = dateLabels(trend.map { it.weekStart }),
                showLegend = false,
                height = 140.dp,
                yLabel = { Format.number(it, 0) }
            )
        }
        if (predictions.isNotEmpty()) {
            Text(text = "Race predictions — estimates", style = MaterialTheme.typography.titleSmall)
            predictions.forEach { p ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(text = p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(text = Format.duration(p.timeSec), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = "Estimates from your VDOT (Daniels). Real results depend on training for the distance, course, weather and pacing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ZoneTablesCard(insights: FitnessInsights, modifier: Modifier = Modifier) {
    if (insights.hrZones.isEmpty() && insights.paceZones.isEmpty()) return
    val units = LocalUnitSystem.current
    val zoneColors = StrideTheme.colors.zones
    StrideCard(modifier = modifier, title = "Your zones") {
        if (insights.hrZones.isNotEmpty()) {
            Text(text = "Heart rate (bpm)", style = MaterialTheme.typography.titleSmall)
            insights.hrZones.forEach { z: HrZone ->
                val range = "${Format.number(z.minBpm, 0)}–${Format.number(z.maxBpm, 0)}"
                ZoneRow("Z${z.zone}", z.name, range, zoneColors[(z.zone - 1).coerceIn(0, 4)])
            }
        }
        if (insights.paceZones.isNotEmpty()) {
            Text(text = "Pace (${Format.paceUnit(units)})", style = MaterialTheme.typography.titleSmall)
            insights.paceZones.forEach { z: PaceZone ->
                val slow = Format.pace(z.minSpeedMps, units)
                val fast = Format.pace(z.maxSpeedMps, units)
                val range = if (z.minSpeedMps <= MIN_SPEED) "slower than $fast" else "$slow–$fast"
                ZoneRow(z.label.name, z.name, range, zoneColors[(z.zone - 1).coerceIn(0, 4)])
            }
        }
    }
}

@Composable
private fun ZoneRow(tag: String, name: String, range: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(text = tag, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(0.5f))
        Text(text = name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
        Text(text = range, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MIN_SPEED = 0.05
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

private fun shortDate(iso: String): String = SHORT_DATE.format(LocalDate.parse(iso))

/** Four evenly spaced date labels. */
private fun dateLabels(dates: List<String>): List<AxisLabel> {
    if (dates.isEmpty()) return emptyList()
    val last = dates.lastIndex
    return listOf(0, last / 3, 2 * last / 3, last).distinct().map { AxisLabel(it, shortDate(dates[it])) }
}
