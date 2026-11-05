package org.stride.feature.activities

import android.content.res.Resources
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import org.stride.core.designsystem.component.ErrorState
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
import org.stride.core.ui.LocalIs24HourClock
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.labelRes

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
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

/** One activity (MVP-015): stats, load, splits, HR and pace over time, HR zones, efficiency. */
@Composable
fun ActivityDetailScreen(
    state: ActivityDetailUiState,
    onBack: () -> Unit,
    onAskCoach: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        val detail = state.detail
        Scaffold(
            modifier = modifier,
            topBar = { StrideTopAppBar(title = detail?.activity?.name ?: stringResource(R.string.run_title), onBack = onBack) }
        ) { padding ->
            when {
                state.loadFailed -> ErrorState(
                    title = stringResource(CoreUiR.string.load_error_title),
                    message = stringResource(CoreUiR.string.load_error_message),
                    onRetry = onRetry,
                    modifier = Modifier.padding(padding)
                )

                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                detail == null -> EmptyState(
                    title = stringResource(R.string.run_not_found_title),
                    message = stringResource(R.string.run_not_found_message),
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
                        text = stringResource(R.string.run_ask_coach),
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
            text = "${Format.dayTime(a.startEpochSec, a.timeZone, LocalIs24HourClock.current)} · ${stringResource(a.sport.labelRes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val res = LocalResources.current
        val tiles = buildList {
            add(Triple(res.getString(R.string.run_distance), Format.distance(a.distanceM, units), Format.distanceUnit(units)))
            add(Triple(res.getString(R.string.run_time), Format.duration(a.movingTimeSec ?: a.durationSec), null))
            add(Triple(res.getString(R.string.run_pace), Format.pace(a.avgSpeedMps, units), Format.paceUnit(units)))
            a.avgHr?.let { add(Triple(res.getString(R.string.run_avg_hr), Format.number(it, 0), res.getString(R.string.run_bpm))) }
            a.elevationGainM?.let { add(Triple(res.getString(R.string.run_elevation), Format.elevation(it, units), null)) }
            a.avgCadenceSpm?.let { add(Triple(res.getString(R.string.run_cadence), Format.number(it, 0), res.getString(R.string.run_spm))) }
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
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.run_training_load),
        subtitle = stringResource(metrics.loadMethod.labelRes)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.run_load),
                value = Format.number(metrics.load, 0),
                caption = stringResource(metrics.loadMethod.shortLabelRes()),
                modifier = Modifier.weight(1f)
            )
            metrics.intensityFactor?.let {
                StatTile(
                    label = stringResource(R.string.run_intensity),
                    value = Format.number(it, 2),
                    caption = stringResource(R.string.run_intensity_caption),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = stringResource(metrics.loadMethod.explanationRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Charts(charts: StreamCharts, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val res = LocalResources.current
    val xLabels = timeLabels(charts.timeSec)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        charts.heartRate?.let { hr ->
            val heartRate = stringResource(R.string.run_heart_rate)
            StrideCard(title = heartRate, subtitle = stringResource(R.string.run_heart_rate_subtitle)) {
                LineChart(
                    series = listOf(LineSeries(heartRate, hr, StrideTheme.colors.zone5)),
                    contentDescription = stringResource(
                        R.string.run_heart_rate_description,
                        rangeText(res, hr, " " + stringResource(R.string.run_bpm)) { Format.number(it, 0) }
                    ),
                    xLabels = xLabels,
                    showLegend = false,
                    height = 160.dp
                )
            }
        }
        charts.paceSecPerKm?.let { pace ->
            val paceLabel = stringResource(R.string.run_pace)
            StrideCard(title = paceLabel, subtitle = stringResource(R.string.run_pace_subtitle, Format.paceUnit(units).removePrefix("/"))) {
                LineChart(
                    series = listOf(LineSeries(paceLabel, pace, StrideTheme.colors.fitness)),
                    contentDescription = stringResource(
                        R.string.run_pace_description,
                        rangeText(res, pace, " ${Format.paceUnit(units)}") { Format.paceFromSecPerKm(it, units) }
                    ),
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
    val title = stringResource(R.string.run_hr_zones)
    val res = LocalResources.current
    StrideCard(modifier = modifier, title = title) {
        ZoneBars(
            rows = shares.mapIndexed { i, share ->
                ZoneBarRow(
                    label = res.getString(R.string.run_zone_short, i + 1),
                    fraction = (share / widest).toFloat(),
                    valueText = "${Format.percent(share)} · ${Format.durationShort(seconds.getOrElse(i) { 0.0 })}",
                    color = colors[i % colors.size]
                )
            },
            contentDescription = shares.mapIndexed { i, s -> res.getString(R.string.run_zone_description, i + 1, Format.percent(s)) }
                .joinToString(prefix = "$title: ")
        )
    }
}

@Composable
private fun SplitsCard(laps: List<Lap>, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(modifier = modifier, title = stringResource(R.string.run_splits), subtitle = stringResource(R.string.run_splits_subtitle)) {
        SplitRow(
            "#",
            Format.distanceUnit(units),
            stringResource(R.string.run_pace),
            stringResource(R.string.run_hr),
            stringResource(R.string.run_elev),
            header = true
        )
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
    StrideCard(modifier = modifier, title = stringResource(R.string.run_efficiency)) {
        metrics.efficiencyFactor?.let { ef ->
            Explained(
                label = stringResource(R.string.run_efficiency_factor),
                value = Format.number(ef, 2),
                text = stringResource(R.string.run_efficiency_factor_text)
            )
        }
        metrics.decouplingPct?.let { pct ->
            Explained(
                label = stringResource(R.string.run_decoupling),
                value = stringResource(R.string.run_percent, Format.number(pct, 1)),
                text = stringResource(R.string.run_decoupling_text)
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
        text = stringResource(if (source == DataSource.GARMIN) R.string.run_attribution_garmin else R.string.run_attribution_demo),
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

private fun rangeText(res: Resources, values: List<Double>, unit: String, format: (Double) -> String): String {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return res.getString(R.string.run_no_data)
    return res.getString(R.string.run_range, format(finite.min()), format(finite.max()), unit)
}

@StringRes
private fun org.stride.core.model.LoadMethod.shortLabelRes(): Int = when (this) {
    org.stride.core.model.LoadMethod.RTSS -> R.string.run_load_rtss
    org.stride.core.model.LoadMethod.TRIMP -> R.string.run_load_trimp
    org.stride.core.model.LoadMethod.DURATION -> R.string.run_load_duration
}

@StringRes
private fun org.stride.core.model.LoadMethod.explanationRes(): Int = when (this) {
    org.stride.core.model.LoadMethod.RTSS -> R.string.run_load_rtss_explanation
    org.stride.core.model.LoadMethod.TRIMP -> R.string.run_load_trimp_explanation
    org.stride.core.model.LoadMethod.DURATION -> R.string.run_load_duration_explanation
}
