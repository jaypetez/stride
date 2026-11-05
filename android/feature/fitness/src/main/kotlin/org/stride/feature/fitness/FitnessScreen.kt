package org.stride.feature.fitness

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.stride.core.designsystem.component.ErrorState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.DailyLoadPoint
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.PaceZone
import org.stride.core.ui.Format
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.R as CoreUiR

@Composable
fun FitnessRoute(modifier: Modifier = Modifier, viewModel: FitnessViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FitnessScreen(state = state, onSelectRange = viewModel::selectRange, onRetry = viewModel::retry, modifier = modifier)
}

/** Fitness (MVP-016): PMC, ACWR, weekly intensity split, VDOT, race estimates and zones. */
@Composable
fun FitnessScreen(
    state: FitnessUiState,
    onSelectRange: (FitnessRange) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = stringResource(R.string.fitness_title)) }) { padding ->
            val insights = state.insights
            when {
                state.loadFailed -> ErrorState(
                    title = stringResource(CoreUiR.string.load_error_title),
                    message = stringResource(CoreUiR.string.load_error_message),
                    onRetry = onRetry,
                    modifier = Modifier.padding(padding)
                )

                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                state.isEmpty || insights == null -> EmptyState(
                    title = stringResource(R.string.fitness_empty_title),
                    message = stringResource(R.string.fitness_empty_message),
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
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text(stringResource(r.labelRes)) })
        }
    }
}

@Composable
private fun PmcCard(window: List<DailyLoadPoint>, rampRatePerWeek: Double?, modifier: Modifier = Modifier) {
    val colors = StrideTheme.colors
    val latest = window.lastOrNull()
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.fitness_pmc_title),
        subtitle = rampRatePerWeek?.let { stringResource(R.string.fitness_pmc_ramp, Format.signed(it, 1)) }
    ) {
        LineChart(
            series = listOf(
                LineSeries(
                    stringResource(R.string.fitness_ctl),
                    window.map {
                        it.ctl
                    },
                    colors.fitness,
                    latest?.let { Format.number(it.ctl, 0) }
                ),
                LineSeries(
                    stringResource(R.string.fitness_atl),
                    window.map {
                        it.atl
                    },
                    colors.fatigue,
                    latest?.let { Format.number(it.atl, 0) }
                ),
                LineSeries(stringResource(R.string.fitness_tsb), window.map { it.tsb }, colors.form, latest?.let { Format.signed(it.tsb) })
            ),
            contentDescription = latest?.let {
                pluralStringResource(
                    R.plurals.fitness_pmc_description,
                    window.size,
                    window.size,
                    Format.number(it.ctl, 0),
                    Format.number(it.atl, 0),
                    Format.signed(it.tsb)
                )
            } ?: stringResource(R.string.fitness_pmc_no_data),
            xLabels = dateLabels(window.map { it.date }),
            zeroLine = true
        )
        Text(
            text = stringResource(R.string.fitness_pmc_explanation),
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
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.fitness_acwr_title),
        subtitle = latest?.let { stringResource(R.string.fitness_acwr_now, Format.number(it, 2)) }
    ) {
        LineChart(
            series = listOf(LineSeries(stringResource(R.string.fitness_acwr), values, colors.fitness)),
            contentDescription = listOfNotNull(
                pluralStringResource(R.plurals.fitness_acwr_window, window.size, window.size),
                latest?.let { stringResource(R.string.fitness_acwr_now_part, Format.number(it, 2)) },
                pluralStringResource(R.plurals.fitness_acwr_spikes, spikes.size, spikes.size, Format.number(ACWR_SPIKE, 1))
            ).joinToString(", ", postfix = "."),
            xLabels = dateLabels(window.map { it.date }),
            band = HorizontalBand(ACWR_SWEET_SPOT_MIN, ACWR_SWEET_SPOT_MAX, colors.fresh, stringResource(R.string.fitness_acwr_sweet_spot)),
            thresholds = listOf(ThresholdLine(ACWR_SPIKE, colors.overreaching, stringResource(R.string.fitness_acwr_spike))),
            markers = spikes.map { ChartMarker(it, colors.overreaching) },
            showLegend = false,
            height = 160.dp
        )
        Text(
            text = stringResource(
                R.string.fitness_acwr_explanation,
                Format.number(ACWR_SWEET_SPOT_MIN, 1),
                Format.number(ACWR_SWEET_SPOT_MAX, 1),
                Format.number(ACWR_SPIKE, 1)
            ),
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
        title = stringResource(R.string.fitness_intensity_title),
        subtitle = stringResource(
            R.string.fitness_intensity_subtitle,
            Format.number(latest.easyPct, 0),
            Format.number(POLARIZED_EASY_TARGET_PCT, 0)
        )
    ) {
        StackedBarChart(
            bars = weeks.map { w ->
                StackedBar(shortDate(w.weekStart), listOf(w.distribution.easyPct, w.distribution.moderatePct, w.distribution.hardPct))
            },
            segmentColors = listOf(colors.zone2, colors.zone3, colors.zone5),
            segmentNames = listOf(
                stringResource(R.string.fitness_easy),
                stringResource(R.string.fitness_moderate),
                stringResource(R.string.fitness_hard)
            ),
            contentDescription = pluralStringResource(
                R.plurals.fitness_intensity_description,
                weeks.size,
                weeks.size,
                Format.number(latest.easyPct, 0),
                Format.number(latest.moderatePct, 0),
                Format.number(latest.hardPct, 0)
            ),
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
        title = stringResource(R.string.fitness_vdot_title),
        subtitle = currentVdot?.let { stringResource(R.string.fitness_vdot_current, Format.number(it, 1)) }
    ) {
        if (trend.size > 1) {
            LineChart(
                series = listOf(LineSeries(stringResource(R.string.fitness_vdot), trend.map { it.vdot }, StrideTheme.colors.fitness)),
                contentDescription = stringResource(
                    R.string.fitness_vdot_description,
                    Format.number(trend.first().vdot, 1),
                    Format.number(trend.last().vdot, 1)
                ),
                xLabels = dateLabels(trend.map { it.weekStart }),
                showLegend = false,
                height = 140.dp,
                yLabel = { Format.number(it, 0) }
            )
        }
        if (predictions.isNotEmpty()) {
            Text(text = stringResource(R.string.fitness_predictions), style = MaterialTheme.typography.titleSmall)
            predictions.forEach { p ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(raceLabelRes(p.distanceM)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = Format.duration(p.timeSec), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = stringResource(R.string.fitness_predictions_note),
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
    StrideCard(modifier = modifier, title = stringResource(R.string.fitness_zones_title)) {
        if (insights.hrZones.isNotEmpty()) {
            Text(text = stringResource(R.string.fitness_zones_hr), style = MaterialTheme.typography.titleSmall)
            insights.hrZones.forEach { z: HrZone ->
                val range = stringResource(R.string.fitness_range_between, Format.number(z.minBpm, 0), Format.number(z.maxBpm, 0))
                ZoneRow(stringResource(R.string.fitness_zone_short, z.zone), z.name, range, zoneColors[(z.zone - 1).coerceIn(0, 4)])
            }
        }
        if (insights.paceZones.isNotEmpty()) {
            Text(text = stringResource(R.string.fitness_zones_pace, Format.paceUnit(units)), style = MaterialTheme.typography.titleSmall)
            insights.paceZones.forEach { z: PaceZone ->
                val slow = Format.pace(z.minSpeedMps, units)
                val fast = Format.pace(z.maxSpeedMps, units)
                val range = if (z.minSpeedMps <= MIN_SPEED) {
                    stringResource(R.string.fitness_slower_than, fast)
                } else {
                    stringResource(R.string.fitness_range_between, slow, fast)
                }
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

private fun shortDate(iso: String): String = Format.shortDate(iso)

/** Race names for the predictions (InsightsRepository.RACES distances). */
@StringRes
private fun raceLabelRes(metres: Double): Int = when {
    metres < 7_500 -> CoreUiR.string.race_5k
    metres < 15_000 -> CoreUiR.string.race_10k
    metres < 30_000 -> CoreUiR.string.race_half
    else -> CoreUiR.string.race_marathon
}

/** Four evenly spaced date labels. */
private fun dateLabels(dates: List<String>): List<AxisLabel> {
    if (dates.isEmpty()) return emptyList()
    val last = dates.lastIndex
    return listOf(0, last / 3, 2 * last / 3, last).distinct().map { AxisLabel(it, shortDate(dates[it])) }
}
