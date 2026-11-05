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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.data.insights.VolumeDelta
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.data.sync.SyncProgress
import org.stride.core.designsystem.component.EmptyState
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
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.color
import org.stride.core.ui.listContentPadding

@Composable
fun TodayRoute(modifier: Modifier = Modifier, viewModel: TodayViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    TodayScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenActivity = { navigator.navigate(ActivityDetailRoute(it)) },
        modifier = modifier
    )
}

/** Today (MVP-014): form, next workout (MVP-017), last run, weekly volume and wellness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, onRefresh: () -> Unit, onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Today") }) { padding ->
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
                        state.isLoading -> item(key = "loading") { LoadingState(label = "Loading your training…") }

                        content == null -> item(key = "empty") {
                            EmptyState(
                                title = "No runs yet",
                                message = "Pull down to sync from Garmin, or try the demo from settings.",
                                actionLabel = "Sync now",
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
            val label = if (sync.total > 0) "Syncing ${sync.phase} · ${sync.done}/${sync.total}" else "Syncing ${sync.phase}…"
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sync.total > 0) {
                LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is SyncProgress.Failed -> Text(
            text = sync.reason.message,
            style = MaterialTheme.typography.bodySmall,
            color = StrideTheme.colors.warning,
            modifier = modifier.fillMaxWidth()
        )

        else -> Spacer(modifier = modifier)
    }
}

@Composable
private fun FormCard(form: DailyLoadPoint?, band: FormBand?, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier, title = "Form", subtitle = form?.let { "TSB · ${Format.day(it.date)}" }) {
        if (form == null) {
            Text(text = "Not enough history to compute form yet.", style = MaterialTheme.typography.bodyMedium)
            return@StrideCard
        }
        val bandColor = band?.color() ?: MaterialTheme.colorScheme.onSurface
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Form ${Format.signed(form.tsb)}${band?.let { ", ${it.label}" }.orEmpty()}"
            }
        ) {
            Text(text = Format.signed(form.tsb), style = MaterialTheme.typography.displayMedium, color = bandColor)
            if (band != null) StatusPill(text = band.label, color = bandColor)
        }
        band?.let {
            Text(text = it.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Fitness",
                value = Format.number(form.ctl, 0),
                unit = "CTL",
                caption = "42-day load",
                accent = StrideTheme.colors.fitness,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Fatigue",
                value = Format.number(form.atl, 0),
                unit = "ATL",
                caption = "7-day load",
                accent = StrideTheme.colors.fatigue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NextWorkoutCard(workout: WorkoutSuggestion, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(modifier = modifier, title = "Next workout", subtitle = workout.type.displayName()) {
        Text(text = workout.title, style = MaterialTheme.typography.titleLarge)
        Text(text = workout.description, style = MaterialTheme.typography.bodyMedium)
        val targets = buildList {
            workout.targetDurationSec?.let { add("Time" to Format.durationShort(it)) }
            workout.targetDistanceM?.let { add("Distance" to Format.distanceWithUnit(it, units, 1)) }
            workout.targetPaceSecPerKm?.let { add("Pace" to "${Format.paceFromSecPerKm(it, units)} ${Format.paceUnit(units)}") }
            workout.targetHrZone?.let { add("HR" to "Zone $it") }
        }
        if (targets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                targets.forEach { (label, value) -> LabelledValue(label, value) }
            }
        }
        Text(
            text = "Why: ${workout.rationale}",
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
        title = "Last run",
        subtitle = "${Format.dayTime(a.startEpochSec, a.timeZone)} · ${a.name}",
        onClick = { onOpenActivity(a.id) }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelledValue("Distance", Format.distanceWithUnit(a.distanceM, units))
            LabelledValue("Time", Format.duration(a.movingTimeSec ?: a.durationSec))
            LabelledValue("Pace", Format.paceWithUnit(a.avgSpeedMps, units))
            run.metrics?.let { LabelledValue("Load", Format.number(it.load, 0)) }
        }
    }
}

@Composable
private fun WeekCard(thisWeek: WeeklyVolume, lastWeek: WeeklyVolume, delta: VolumeDelta, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    StrideCard(modifier = modifier, title = "This week", subtitle = "Compared with last week") {
        WeekRow("", "This week", "Last week", "Change", header = true)
        WeekRow(
            "Distance (${Format.distanceUnit(units)})",
            Format.distance(thisWeek.distanceM, units, 1),
            Format.distance(lastWeek.distanceM, units, 1),
            Format.signedDistance(delta.distanceM, units)
        )
        WeekRow(
            "Time",
            Format.durationShort(thisWeek.durationSec),
            Format.durationShort(lastWeek.durationSec),
            Format.signedDurationShort(delta.durationSec)
        )
        WeekRow("Load", Format.number(thisWeek.load, 0), Format.number(lastWeek.load, 0), Format.signed(delta.load))
        WeekRow("Runs", thisWeek.runs.toString(), lastWeek.runs.toString(), Format.signed(delta.runs.toDouble()))
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
        Text(text = label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
        Text(text = current, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = previous, style = style, color = color, modifier = Modifier.weight(1f))
        Text(text = change, style = style, color = color, modifier = Modifier.weight(0.9f))
    }
}

@Composable
private fun WellnessSection(w: DailyWellness, modifier: Modifier = Modifier) {
    val tiles = buildList {
        w.restingHr?.let { add(WellnessTile("Resting HR", it.toString(), "bpm", null)) }
        w.hrvLastNightMs?.let {
            add(WellnessTile("HRV", Format.number(it, 0), "ms", w.hrvStatus?.lowercase()?.replaceFirstChar { c -> c.uppercase() }))
        }
        w.sleepSec?.let { add(WellnessTile("Sleep", Format.durationShort(it.toDouble()), null, w.sleepScore?.let { s -> "Score $s" })) }
        if (w.bodyBatteryHigh != null || w.bodyBatteryLow != null) {
            add(WellnessTile("Body Battery", w.bodyBatteryHigh?.toString() ?: "–", null, w.bodyBatteryLow?.let { "Low $it" }))
        }
        w.stressAvg?.let { add(WellnessTile("Stress", it.toString(), "avg", null)) }
    }
    if (tiles.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Wellness · ${Format.day(w.date)}")
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
