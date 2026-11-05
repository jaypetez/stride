package org.stride.feature.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.designsystem.chart.AxisLabel
import org.stride.core.designsystem.chart.LineChart
import org.stride.core.designsystem.chart.LineSeries
import org.stride.core.designsystem.chart.ZoneBarRow
import org.stride.core.designsystem.chart.ZoneBars
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StatTile
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.ActivityDetail
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.DataSource
import org.stride.core.model.Lap
import org.stride.core.navigation.CoachRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.ui.Format
import org.stride.core.ui.LocalUnitSystem

@Composable
fun ActivityDetailFlow(activityId: Long, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ActivityDetailViewModel, ActivityDetailViewModel.Factory>(
        key = "activity-$activityId",
        creationCallback = { it.create(activityId) }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ActivityDetailScreen(
        state = state,
        onBack = { navigator.goBack() },
        onAskCoach = { navigator.navigate(CoachRoute(activityId)) },
        modifier = modifier
    )
}

/** One activity (MVP-015): stats, load, splits, HR and pace over time, HR zones, efficiency. */
@Composable
fun ActivityDetailScreen(state: ActivityDetailUiState, onBack: () -> Unit, onAskCoach: () -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        val detail = state.detail
        Scaffold(
            modifier = modifier,
            topBar = { StrideTopAppBar(title = detail?.activity?.name ?: "Run", onBack = onBack) }
        ) { padding ->
            when {
                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                detail == null -> EmptyState(
                    title = "Run not found",
                    message = "This activity is no longer on your device.",
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
                    HeaderStats(detail)
                    detail.metrics?.let { LoadCard(it) }
                    state.charts?.let { Charts(it) }
                    if (state.zoneShares.isNotEmpty()) HrZonesCard(state.zoneShares, detail.metrics?.hrZoneSeconds.orEmpty())
                    if (detail.laps.isNotEmpty()) SplitsCard(detail.laps)
                    detail.metrics?.let { EfficiencyCard(it) }
                    StrideButton(
                        text = "Ask the coach about this run",
                        onClick = onAskCoach,
                        leadingIcon = Icons.AutoMirrored.Outlined.Chat,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Attribution(detail.activity.source)
                }
            }
        }
    }
}

@Composable
private fun HeaderStats(detail: ActivityDetail, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val a = detail.activity
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "${Format.dayTime(a.startEpochSec, a.timeZone)} · ${a.sport.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val tiles = buildList {
            add(Triple("Distance", Format.distance(a.distanceM, units), Format.distanceUnit(units)))
            add(Triple("Time", Format.duration(a.movingTimeSec ?: a.durationSec), null))
            add(Triple("Pace", Format.pace(a.avgSpeedMps, units), Format.paceUnit(units)))
            a.avgHr?.let { add(Triple("Avg HR", Format.number(it, 0), "bpm")) }
            a.elevationGainM?.let { add(Triple("Elevation", Format.elevation(it, units), null)) }
            a.avgCadenceSpm?.let { add(Triple("Cadence", Format.number(it, 0), "spm")) }
        }
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, unit) -> StatTile(label = label, value = value, unit = unit, modifier = Modifier.weight(1f)) }
                repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LoadCard(metrics: ActivityMetrics, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier, title = "Training load", subtitle = metrics.loadMethod.label) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Load",
                value = Format.number(metrics.load, 0),
                caption = metrics.loadMethod.shortLabel(),
                modifier = Modifier.weight(1f)
            )
            metrics.intensityFactor?.let {
                StatTile(label = "Intensity", value = Format.number(it, 2), caption = "IF vs threshold", modifier = Modifier.weight(1f))
            }
        }
        Text(
            text = metrics.loadMethod.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Charts(charts: StreamCharts, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val xLabels = timeLabels(charts.timeSec)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        charts.heartRate?.let { hr ->
            StrideCard(title = "Heart rate", subtitle = "bpm over time") {
                LineChart(
                    series = listOf(LineSeries("Heart rate", hr, StrideTheme.colors.zone5)),
                    contentDescription = "Heart rate over time, ${rangeText(hr, " bpm") { Format.number(it, 0) }}",
                    xLabels = xLabels,
                    showLegend = false,
                    height = 160.dp
                )
            }
        }
        charts.paceSecPerKm?.let { pace ->
            StrideCard(title = "Pace", subtitle = "${Format.paceUnit(units).removePrefix("/")} pace over time · faster is higher") {
                LineChart(
                    series = listOf(LineSeries("Pace", pace, StrideTheme.colors.fitness)),
                    contentDescription = "Pace over time, " +
                        rangeText(pace, " ${Format.paceUnit(units)}") { Format.paceFromSecPerKm(it, units) },
                    xLabels = xLabels,
                    invertY = true,
                    showLegend = false,
                    height = 160.dp,
                    yLabel = { Format.paceFromSecPerKm(it, units) }
                )
            }
        }
    }
}

@Composable
private fun HrZonesCard(shares: List<Double>, seconds: List<Double>, modifier: Modifier = Modifier) {
    val colors = StrideTheme.colors.zones
    val widest = shares.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    StrideCard(modifier = modifier, title = "Time in heart-rate zones") {
        ZoneBars(
            rows = shares.mapIndexed { i, share ->
                ZoneBarRow(
                    label = "Z${i + 1}",
                    fraction = (share / widest).toFloat(),
                    valueText = "${Format.percent(share)} · ${Format.durationShort(seconds.getOrElse(i) { 0.0 })}",
                    color = colors[i % colors.size]
                )
            },
            contentDescription = shares.mapIndexed { i, s -> "zone ${i + 1} ${Format.percent(s)}" }
                .joinToString(prefix = "Time in heart-rate zones: ")
        )
    }
}

@Composable
private fun SplitsCard(laps: List<Lap>, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(modifier = modifier, title = "Splits", subtitle = "Per lap from the watch") {
        SplitRow("#", Format.distanceUnit(units), "Pace", "HR", "Elev", header = true)
        laps.forEach { lap ->
            SplitRow(
                (lap.index + 1).toString(),
                Format.distance(lap.distanceM, units),
                Format.pace(lap.avgSpeedMps, units),
                lap.avgHr?.let { Format.number(it, 0) } ?: "–",
                lap.elevationGainM?.let { "+${Format.elevation(it, units)}" } ?: "–"
            )
        }
    }
}

@Composable
private fun SplitRow(
    index: String,
    distance: String,
    pace: String,
    hr: String,
    elevation: String,
    modifier: Modifier = Modifier,
    header: Boolean = false
) {
    val style = if (header) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal)
    }
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = modifier.fillMaxWidth().then(if (header) Modifier.semantics { heading() } else Modifier)) {
        Text(index, style = style, color = color, modifier = Modifier.weight(0.5f))
        Text(distance, style = style, color = color, modifier = Modifier.weight(1f))
        Text(pace, style = style, color = color, modifier = Modifier.weight(1f))
        Text(hr, style = style, color = color, modifier = Modifier.weight(0.8f))
        Text(elevation, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EfficiencyCard(metrics: ActivityMetrics, modifier: Modifier = Modifier) {
    if (metrics.efficiencyFactor == null && metrics.decouplingPct == null) return
    StrideCard(modifier = modifier, title = "Aerobic efficiency") {
        metrics.efficiencyFactor?.let { ef ->
            Explained(
                label = "Efficiency factor",
                value = Format.number(ef, 2),
                text = "Grade-adjusted speed (m/min) per heartbeat. A rising EF at the same effort means your aerobic fitness is improving."
            )
        }
        metrics.decouplingPct?.let { pct ->
            Explained(
                label = "Aerobic decoupling",
                value = "${Format.number(pct, 1)} %",
                text = "How far pace and heart rate drifted apart from the first half to the second. " +
                    "Under 5 % is good; over 10 % suggests building more base before adding intensity."
            )
        }
    }
}

@Composable
private fun Explained(label: String, value: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(text = value, style = MaterialTheme.typography.titleSmall)
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Garmin brand guidelines: attribute data that came from Garmin; demo data says it is synthetic. */
@Composable
private fun Attribution(source: DataSource, modifier: Modifier = Modifier) {
    Text(
        text = if (source == DataSource.GARMIN) "Data from Garmin Connect" else "Synthetic demo data — not from Garmin",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

private fun timeLabels(timeSec: List<Double>): List<AxisLabel> {
    if (timeSec.size < 2) return emptyList()
    val last = timeSec.lastIndex
    return listOf(0, last / 3, 2 * last / 3, last).distinct().map { AxisLabel(it, Format.duration(timeSec[it])) }
}

private fun rangeText(values: List<Double>, unit: String, format: (Double) -> String): String {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return "no data"
    return "from ${format(finite.min())} to ${format(finite.max())}$unit"
}

private fun org.stride.core.model.LoadMethod.shortLabel(): String = when (this) {
    org.stride.core.model.LoadMethod.RTSS -> "rTSS"
    org.stride.core.model.LoadMethod.TRIMP -> "TRIMP"
    org.stride.core.model.LoadMethod.DURATION -> "Duration estimate"
}

private fun org.stride.core.model.LoadMethod.explanation(): String = when (this) {
    org.stride.core.model.LoadMethod.RTSS ->
        "Pace-based: grade-adjusted pace relative to your threshold, weighted by duration. 100 ≈ one hour at threshold."

    org.stride.core.model.LoadMethod.TRIMP ->
        "Heart-rate based (Banister TRIMP): no reliable pace, so load comes from heart-rate reserve and duration."

    org.stride.core.model.LoadMethod.DURATION -> "Estimated from duration only: no usable pace or heart rate for this activity."
}
