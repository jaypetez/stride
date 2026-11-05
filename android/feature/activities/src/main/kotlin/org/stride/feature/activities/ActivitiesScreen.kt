package org.stride.feature.activities

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.navigation.ActivityDetailRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.ui.Format
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.listContentPadding

@Composable
fun ActivitiesRoute(modifier: Modifier = Modifier, viewModel: ActivitiesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ActivitiesScreen(state = state, onOpenActivity = { navigator.navigate(ActivityDetailRoute(it)) }, modifier = modifier)
}

/** Runs (MVP-015): every synced activity, newest first, grouped by month. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivitiesScreen(state: ActivitiesUiState, onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Runs") }) { padding ->
            when {
                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                state.months.isEmpty() -> EmptyState(
                    title = "No runs yet",
                    message = "Your Garmin activities appear here after the first sync.",
                    modifier = Modifier.padding(padding)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = listContentPadding()
                ) {
                    state.months.forEach { month ->
                        stickyHeader(key = "month-${month.key}", contentType = "header") {
                            Text(
                                text = month.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 8.dp)
                            )
                        }
                        items(month.activities, key = { it.activity.id }, contentType = { "activity" }) { item ->
                            ActivityRow(item = item, onClick = { onOpenActivity(item.activity.id) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityWithMetrics, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val a = item.activity
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open run", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = a.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Format.dayTime(a.startEpochSec, a.timeZone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(Format.distanceWithUnit(a.distanceM, units))
            Metric(Format.duration(a.movingTimeSec ?: a.durationSec))
            if (a.sport.isRun) Metric(Format.paceWithUnit(a.avgSpeedMps, units))
            item.metrics?.let { Metric("Load ${Format.number(it.load, 0)}") }
        }
    }
}

@Composable
private fun Metric(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
