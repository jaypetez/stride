package org.stride.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.SectionHeader
import org.stride.core.designsystem.component.StatusPill
import org.stride.core.designsystem.component.StrideAvatar
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideOutlinedButton
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.UnitSystem
import org.stride.core.navigation.CoachRoute
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.LicencesRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.navigation.ProfileEditRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.ui.Format
import org.stride.core.ui.GARMIN_ATTRIBUTION
import org.stride.core.ui.PRIVACY_NOTE

/** Stateful "You" tab. */
@Composable
fun SettingsFlow(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    LaunchedEffect(viewModel, navigator) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // Preferences are wiped too, so the shell swaps to onboarding; clear every tab's stack.
                SettingsEffect.DataWiped -> navigator.resetTo(TodayRoute)
            }
        }
    }
    SettingsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onNavigate = navigator::navigate,
        onExport = { exportLauncher.launch(viewModel.exportFileName()) },
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onNavigate: (NavKey) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onEvent(SettingsEvent.MessageShown)
        }
    }
    Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "You") }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StrideCard(onClick = { onNavigate(ProfileEditRoute) }) {
                ListItem(
                    headlineContent = { Text(state.profile.name.ifBlank { "Your profile" }) },
                    supportingContent = { Text(profileSummary(state)) },
                    leadingContent = { StrideAvatar(name = state.profile.name.ifBlank { "?" }) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Edit profile") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            SectionHeader("Display")
            StrideCard {
                Text("Units", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { units ->
                        FilterChip(
                            selected = state.units == units,
                            onClick = { onEvent(SettingsEvent.UnitsChanged(units)) },
                            label = { Text(if (units == UnitSystem.METRIC) "Kilometres" else "Miles") }
                        )
                    }
                }
                SwitchRow(
                    title = "Dynamic colour",
                    subtitle = "Use colours from your wallpaper (Android 12+)",
                    checked = state.dynamicColor,
                    onChange = { onEvent(SettingsEvent.DynamicColorChanged(it)) }
                )
            }

            SectionHeader("Garmin and sync")
            StrideCard(
                title = "Garmin Connect",
                subtitle = when {
                    state.garminKeyRejected -> "Garmin rejected your key. Tap to reconnect."
                    state.garminConnected -> "Connected"
                    else -> "Not connected"
                },
                onClick = { onNavigate(ConnectGarminRoute) },
                trailing = {
                    StatusPill(
                        text = if (state.garminConnected && !state.garminKeyRejected) "On" else "Off",
                        color = if (state.garminKeyRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            ) {}
            StrideCard {
                Text("Sync history", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SYNC_WINDOWS.forEach { days ->
                        FilterChip(
                            selected = state.syncWindowDays == days,
                            onClick = { onEvent(SettingsEvent.SyncWindowChanged(days)) },
                            label = { Text(if (days == 365) "1 year" else "$days days") }
                        )
                    }
                }
                Text(
                    "Last sync: ${state.lastSync ?: "never"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                StrideOutlinedButton(
                    text = "Sync now",
                    onClick = { onEvent(SettingsEvent.SyncNow) },
                    enabled = state.garminConnected && !state.syncing && !state.demoMode,
                    leadingIcon = Icons.Outlined.Sync
                )
            }

            SectionHeader("Demo mode")
            StrideCard(
                subtitle = if (state.demoMode) {
                    "You're looking at synthetic runs. Exiting deletes them; your own data is untouched."
                } else {
                    "Explore Stride with synthetic runs. Your own data is kept separately."
                }
            ) {
                if (state.demoMode) {
                    StrideOutlinedButton(text = "Exit demo", onClick = { onEvent(SettingsEvent.ExitDemo) }, enabled = !state.busy)
                } else {
                    StrideOutlinedButton(text = "Try demo", onClick = { onEvent(SettingsEvent.EnterDemo) }, enabled = !state.busy)
                }
            }

            SectionHeader("AI coach")
            StrideCard(
                subtitle = if (state.coachEnabled) {
                    "On. Uses your own Anthropic key and sends only computed facts."
                } else {
                    "Off. Optional: explains your runs and fitness with your own Anthropic key."
                }
            ) {
                StrideButton(text = "Ask the coach", onClick = { onNavigate(CoachRoute()) })
                if (state.coachEnabled) StrideOutlinedButton(text = "Turn off coach", onClick = { onEvent(SettingsEvent.TurnOffCoach) })
            }

            SectionHeader("Your data")
            StrideCard {
                ActionRow(Icons.Outlined.FileDownload, "Export all data", "Save your runs, health data and metrics as JSON", onExport)
                ActionRow(Icons.Outlined.DeleteForever, "Delete all data", "Remove everything Stride stores on this phone") {
                    onEvent(SettingsEvent.DeleteClicked)
                }
            }

            SectionHeader("About")
            StrideCard {
                Text("Stride ${state.version}", style = MaterialTheme.typography.titleSmall)
                Text(PRIVACY_NOTE, style = MaterialTheme.typography.bodyMedium)
                Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(GARMIN_ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow(null, "Open-source licences", null) { onNavigate(LicencesRoute) }
            }
        }
        if (state.confirmDelete) DeleteDialog(onEvent)
    }
}

private fun profileSummary(state: SettingsUiState): String {
    val p = state.profile
    val parts = buildList {
        p.birthYear?.let { add("Born $it") }
        add(if (p.unitSystem == UnitSystem.IMPERIAL) "Miles" else "Kilometres")
        p.maxHr?.let { add("Max HR $it") }
        p.raceGoal?.let { add("Race ${Format.day(it.date)}") }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
    )
}

@Composable
private fun ActionRow(icon: ImageVector?, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
private fun DeleteDialog(onEvent: (SettingsEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.DeleteDismissed) },
        title = { Text("Delete all data?") },
        text = {
            Text(
                "This deletes your runs, health data, metrics, profile, settings, Garmin key and Anthropic key from this phone. " +
                    "It can't be undone. Your data in Garmin Connect is not affected."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onEvent(SettingsEvent.DeleteConfirmed)
            }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { onEvent(SettingsEvent.DeleteDismissed) }) { Text("Cancel") } }
    )
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    StrideTheme {
        SettingsScreen(state = SettingsUiState(loading = false, version = "0.1.0"), onEvent = {}, onNavigate = {}, onExport = {})
    }
}
