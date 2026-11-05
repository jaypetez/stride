package org.stride.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.data.insights.VolumeDelta
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.data.sync.SyncProgress
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.designsystem.component.ErrorState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.SectionHeader
import org.stride.core.designsystem.component.StatTile
import org.stride.core.designsystem.component.StatusPill
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DailyWellness
import org.stride.core.model.FormBand
import org.stride.core.navigation.ActivityDetailRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.ui.Format
import org.stride.core.ui.LocalIs24HourClock
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.color
import org.stride.core.ui.descriptionRes
import org.stride.core.ui.labelRes
import org.stride.core.ui.listContentPadding

@Composable
fun TodayRoute(modifier: Modifier = Modifier, viewModel: TodayViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    TodayScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenActivity = { navigator.navigate(ActivityDetailRoute(it)) },
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

/** Today (MVP-014): form, next workout (MVP-017), last run, weekly volume and wellness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = stringResource(R.string.today_title)) }) { padding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                val content = state.content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "sync") { SyncStatus(state.sync) }
                    when {
                        state.loadFailed -> item(key = "error") {
                            ErrorState(
                                title = stringResource(CoreUiR.string.load_error_title),
                                message = stringResource(CoreUiR.string.load_error_message),
                                onRetry = onRetry
                            )
                        }

                        state.isLoading -> item(key = "loading") { LoadingState(label = stringResource(R.string.today_loading)) }

                        content == null -> item(key = "empty") {
                            EmptyState(
                                title = stringResource(R.string.today_empty_title),
                                message = stringResource(R.string.today_empty_message),
                                actionLabel = stringResource(R.string.today_sync_now),
                                onAction = onRefresh
                            )
                        }

                        else -> {
                            item(key = "form") { FormCard(content.form, content.band) }
                            content.nextWorkout?.let { item(key = "next") { NextWorkoutCard(it) } }
                            content.lastRun?.let { item(key = "last") { LastRunCard(it, onOpenActivity) } }
                            item(key = "week") { WeekCard(content.thisWeek, content.lastWeek, content.weekDelta) }
                            content.wellness?.let { w -> item(key = "wellness") { WellnessSection(w) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatus(sync: SyncProgress, modifier: Modifier = Modifier) {
    when (sync) {
        is SyncProgress.Running -> Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val phase = stringResource(sync.phase.labelRes)
            val label = if (sync.total > 0) {
                stringResource(R.string.today_syncing_progress, phase, sync.done, sync.total)
            } else {
                stringResource(R.string.today_syncing, phase)
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sync.total > 0) {
                LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is SyncProgress.Failed -> Text(
            text = stringResource(sync.reason.messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = StrideTheme.colors.warning,
            modifier = modifier.fillMaxWidth()
        )

        else -> Spacer(modifier = modifier)
    }
}

@Composable
private fun FormCard(form: DailyLoadPoint?, band: FormBand?, modifier: Modifier = Modifier) {
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.today_form),
        subtitle = form?.let { stringResource(R.string.today_form_subtitle, Format.day(it.date)) }
    ) {
        if (form == null) {
            Text(text = stringResource(R.string.today_form_not_enough), style = MaterialTheme.typography.bodyMedium)
            return@StrideCard
        }
        val bandColor = band?.color() ?: MaterialTheme.colorScheme.onSurface
        val bandLabel = band?.let { stringResource(it.labelRes) }
        val formDescription = if (bandLabel != null) {
            stringResource(R.string.today_form_description_band, Format.signed(form.tsb), bandLabel)
        } else {
            stringResource(R.string.today_form_description, Format.signed(form.tsb))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = formDescription
            }
        ) {
            Text(text = Format.signed(form.tsb), style = MaterialTheme.typography.displayMedium, color = bandColor)
            if (bandLabel != null) StatusPill(text = bandLabel, color = bandColor)
        }
        band?.let {
            Text(
                text = stringResource(it.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.today_fitness),
                value = Format.number(form.ctl, 0),
                unit = stringResource(R.string.today_ctl),
                caption = stringResource(R.string.today_fitness_caption),
                accent = StrideTheme.colors.fitness,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.today_fatigue),
                value = Format.number(form.atl, 0),
                unit = stringResource(R.string.today_atl),
                caption = stringResource(R.string.today_fatigue_caption),
                accent = StrideTheme.colors.fatigue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NextWorkoutCard(workout: WorkoutSuggestion, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(modifier = modifier, title = stringResource(R.string.today_next_workout), subtitle = workout.type.displayName()) {
        Text(text = workout.title, style = MaterialTheme.typography.titleLarge)
        Text(text = workout.description, style = MaterialTheme.typography.bodyMedium)
        val time = stringResource(R.string.today_time)
        val distance = stringResource(R.string.today_distance)
        val pace = stringResource(R.string.today_pace)
        val hr = stringResource(R.string.today_hr)
        val zone = workout.targetHrZone?.let { stringResource(R.string.today_hr_zone, it) }
        val targets = buildList {
            workout.targetDurationSec?.let { add(time to Format.durationShort(it)) }
            workout.targetDistanceM?.let { add(distance to Format.distanceWithUnit(it, units, 1)) }
            workout.targetPaceSecPerKm?.let { add(pace to "${Format.paceFromSecPerKm(it, units)} ${Format.paceUnit(units)}") }
            zone?.let { add(hr to it) }
        }
        if (targets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                targets.forEach { (label, value) -> LabelledValue(label, value) }
            }
        }
        Text(
            text = stringResource(R.string.today_why, workout.rationale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LastRunCard(run: ActivityWithMetrics, onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val a = run.activity
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.today_last_run),
        subtitle = "${Format.dayTime(a.startEpochSec, a.timeZone, LocalIs24HourClock.current)} · ${a.name}",
        onClick = { onOpenActivity(a.id) }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelledValue(stringResource(R.string.today_distance), Format.distanceWithUnit(a.distanceM, units))
            LabelledValue(stringResource(R.string.today_time), Format.duration(a.movingTimeSec ?: a.durationSec))
            LabelledValue(stringResource(R.string.today_pace), Format.paceWithUnit(a.avgSpeedMps, units))
            run.metrics?.let { LabelledValue(stringResource(R.string.today_load), Format.number(it.load, 0)) }
        }
    }
}

@Composable
private fun WeekCard(thisWeek: WeeklyVolume, lastWeek: WeeklyVolume, delta: VolumeDelta, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(
        modifier = modifier,
        title = stringResource(R.string.today_this_week),
        subtitle = stringResource(R.string.today_week_subtitle)
    ) {
        WeekRow(
            "",
            stringResource(R.string.today_this_week),
            stringResource(R.string.today_last_week),
            stringResource(R.string.today_change),
            header = true
        )
        WeekRow(
            stringResource(R.string.today_distance_unit, Format.distanceUnit(units)),
            Format.distance(thisWeek.distanceM, units, 1),
            Format.distance(lastWeek.distanceM, units, 1),
            Format.signedDistance(delta.distanceM, units)
        )
        WeekRow(
            stringResource(R.string.today_time),
            Format.durationShort(thisWeek.durationSec),
            Format.durationShort(lastWeek.durationSec),
            Format.signedDurationShort(delta.durationSec)
        )
        WeekRow(
            stringResource(R.string.today_load),
            Format.number(thisWeek.load, 0),
            Format.number(lastWeek.load, 0),
            Format.signed(delta.load)
        )
        WeekRow(
            stringResource(R.string.today_runs),
            thisWeek.runs.toString(),
            lastWeek.runs.toString(),
            Format.signed(delta.runs.toDouble())
        )
    }
}

@Composable
private fun WeekRow(
    label: String,
    current: String,
    previous: String,
    change: String,
    modifier: Modifier = Modifier,
    header: Boolean = false
) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // An empty Text is an unlabelled node for TalkBack (caught by the ATF check); leave the header corner blank instead.
        if (label.isEmpty()) {
            Spacer(modifier = Modifier.weight(1.4f))
        } else {
            Text(text = label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
        }
        Text(text = current, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = previous, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = change, style = style, color = color, modifier = Modifier.weight(0.9f))
    }
}

@Composable
private fun WellnessSection(w: DailyWellness, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val tiles = buildList {
        w.restingHr?.let {
            add(WellnessTile(res.getString(R.string.today_resting_hr), it.toString(), res.getString(R.string.today_bpm), null))
        }
        w.hrvLastNightMs?.let {
            val status = w.hrvStatus?.lowercase()?.replaceFirstChar { c -> c.uppercase() }
            add(WellnessTile(res.getString(R.string.today_hrv), Format.number(it, 0), res.getString(R.string.today_ms), status))
        }
        w.sleepSec?.let {
            val score = w.sleepScore?.let { s -> res.getString(R.string.today_sleep_score, s) }
            add(WellnessTile(res.getString(R.string.today_sleep), Format.durationShort(it.toDouble()), null, score))
        }
        if (w.bodyBatteryHigh != null || w.bodyBatteryLow != null) {
            val low = w.bodyBatteryLow?.let { res.getString(R.string.today_body_battery_low, it) }
            add(WellnessTile(res.getString(R.string.today_body_battery), w.bodyBatteryHigh?.toString() ?: "–", null, low))
        }
        w.stressAvg?.let { add(WellnessTile(res.getString(R.string.today_stress), it.toString(), res.getString(R.string.today_avg), null)) }
    }
    if (tiles.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = stringResource(R.string.today_wellness, Format.day(w.date)))
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { t ->
                    StatTile(label = t.label, value = t.value, unit = t.unit, caption = t.caption, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

private data class WellnessTile(val label: String, val value: String, val unit: String?, val caption: String?)

@Composable
internal fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

internal fun org.stride.core.science.model.WorkoutType.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar {
    it.uppercase()
}
