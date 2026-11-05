package org.stride.feature.settings

import android.content.res.Resources
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import org.stride.core.designsystem.component.ErrorState
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
import org.stride.core.ui.LocalIs24HourClock
import org.stride.core.ui.R as CoreUiR

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
    val message = state.message?.resolve()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            onEvent(SettingsEvent.MessageShown)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { StrideTopAppBar(title = stringResource(R.string.settings_title)) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.loadFailed) {
            ErrorState(
                title = stringResource(CoreUiR.string.load_error_title),
                message = stringResource(CoreUiR.string.load_error_message),
                onRetry = { onEvent(SettingsEvent.Retry) },
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
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
                    headlineContent = { Text(state.profile.name.ifBlank { stringResource(R.string.settings_your_profile) }) },
                    supportingContent = { Text(profileSummary(LocalResources.current, state)) },
                    leadingContent = { StrideAvatar(name = state.profile.name.ifBlank { "?" }) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_edit_profile)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            SectionHeader(stringResource(R.string.settings_display))
            StrideCard {
                Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { units ->
                        FilterChip(
                            selected = state.units == units,
                            onClick = { onEvent(SettingsEvent.UnitsChanged(units)) },
                            label = {
                                Text(
                                    stringResource(
                                        if (units ==
                                            UnitSystem.METRIC
                                        ) {
                                            CoreUiR.string.units_kilometres
                                        } else {
                                            CoreUiR.string.units_miles
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_colour),
                    subtitle = stringResource(R.string.settings_dynamic_colour_subtitle),
                    checked = state.dynamicColor,
                    onChange = { onEvent(SettingsEvent.DynamicColorChanged(it)) }
                )
            }

            SectionHeader(stringResource(R.string.settings_garmin_sync))
            StrideCard(
                title = stringResource(R.string.settings_garmin_connect),
                subtitle = when {
                    state.garminKeyRejected -> stringResource(R.string.settings_garmin_rejected)
                    state.garminConnected -> stringResource(R.string.settings_garmin_connected)
                    else -> stringResource(R.string.settings_garmin_not_connected)
                },
                onClick = { onNavigate(ConnectGarminRoute) },
                trailing = {
                    StatusPill(
                        text = stringResource(
                            if (state.garminConnected &&
                                !state.garminKeyRejected
                            ) {
                                R.string.settings_on
                            } else {
                                R.string.settings_off
                            }
                        ),
                        color = if (state.garminKeyRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            ) {}
            StrideCard {
                Text(stringResource(R.string.settings_sync_history), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SYNC_WINDOWS.forEach { days ->
                        FilterChip(
                            selected = state.syncWindowDays == days,
                            onClick = { onEvent(SettingsEvent.SyncWindowChanged(days)) },
                            label = {
                                Text(
                                    if (days ==
                                        365
                                    ) {
                                        stringResource(R.string.settings_one_year)
                                    } else {
                                        pluralStringResource(R.plurals.settings_days, days, days)
                                    }
                                )
                            }
                        )
                    }
                }
                val lastSync = state.lastSync?.let { Format.dateTime(it, state.zone, LocalIs24HourClock.current) }
                Text(
                    stringResource(R.string.settings_last_sync, lastSync ?: stringResource(R.string.settings_never)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.syncStatus?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall) }
                StrideOutlinedButton(
                    text = stringResource(R.string.settings_sync_now),
                    onClick = { onEvent(SettingsEvent.SyncNow) },
                    enabled = state.garminConnected && !state.syncing && !state.demoMode,
                    leadingIcon = Icons.Outlined.Sync
                )
            }

            SectionHeader(stringResource(R.string.settings_demo_mode))
            StrideCard(
                subtitle = stringResource(if (state.demoMode) R.string.settings_demo_on else R.string.settings_demo_off)
            ) {
                if (state.demoMode) {
                    StrideOutlinedButton(
                        text = stringResource(R.string.settings_exit_demo),
                        onClick = { onEvent(SettingsEvent.ExitDemo) },
                        enabled = !state.busy
                    )
                } else {
                    StrideOutlinedButton(
                        text = stringResource(R.string.settings_try_demo),
                        onClick = { onEvent(SettingsEvent.EnterDemo) },
                        enabled = !state.busy
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_ai_coach))
            StrideCard(
                subtitle = stringResource(if (state.coachEnabled) R.string.settings_coach_on else R.string.settings_coach_off)
            ) {
                StrideButton(text = stringResource(R.string.settings_ask_coach), onClick = { onNavigate(CoachRoute()) })
                if (state.coachEnabled) {
                    StrideOutlinedButton(text = stringResource(R.string.settings_turn_off_coach), onClick = {
                        onEvent(SettingsEvent.TurnOffCoach)
                    })
                }
            }

            SectionHeader(stringResource(R.string.settings_your_data))
            StrideCard {
                ActionRow(
                    Icons.Outlined.FileDownload,
                    stringResource(R.string.settings_export),
                    stringResource(R.string.settings_export_subtitle),
                    onExport
                )
                ActionRow(
                    Icons.Outlined.DeleteForever,
                    stringResource(R.string.settings_delete),
                    stringResource(R.string.settings_delete_subtitle)
                ) {
                    onEvent(SettingsEvent.DeleteClicked)
                }
            }

            SectionHeader(stringResource(R.string.settings_about))
            StrideCard {
                Text(stringResource(R.string.settings_version, state.version), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(CoreUiR.string.privacy_note), style = MaterialTheme.typography.bodyMedium)
                Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(CoreUiR.string.garmin_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionRow(null, stringResource(R.string.settings_licences), null) { onNavigate(LicencesRoute) }
            }
        }
        if (state.confirmDelete) DeleteDialog(onEvent)
    }
}

private fun profileSummary(res: Resources, state: SettingsUiState): String {
    val p = state.profile
    val parts = buildList {
        p.birthYear?.let { add(res.getString(R.string.settings_born, it)) }
        add(res.getString(if (p.unitSystem == UnitSystem.IMPERIAL) CoreUiR.string.units_miles else CoreUiR.string.units_kilometres))
        p.maxHr?.let { add(res.getString(R.string.settings_max_hr, it)) }
        p.raceGoal?.let { add(res.getString(R.string.settings_race, Format.day(it.date))) }
    }
    return parts.joinToString(" · ")
}

/**
 * One focus target with the switch role (AUD-035): the whole row toggles and
 * TalkBack reads title, subtitle and state together; the Switch itself is
 * not separately focusable (onCheckedChange = null).
 */
@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
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
        title = { Text(stringResource(R.string.settings_delete_title)) },
        text = { Text(stringResource(R.string.settings_delete_text)) },
        confirmButton = {
            TextButton(onClick = {
                onEvent(SettingsEvent.DeleteConfirmed)
            }) { Text(stringResource(R.string.settings_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.DeleteDismissed) }) { Text(stringResource(CoreUiR.string.common_cancel)) }
        }
    )
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    StrideTheme {
        SettingsScreen(state = SettingsUiState(loading = false, version = "0.1.0"), onEvent = {}, onNavigate = {}, onExport = {})
    }
}
