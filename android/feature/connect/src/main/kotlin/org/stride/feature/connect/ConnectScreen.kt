package org.stride.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import org.stride.core.designsystem.component.ErrorState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StatusPill
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideOutlinedButton
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.LocalNavigator
import org.stride.core.ui.Format
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.formInsets
import org.stride.core.ui.profile.toUtcDate
import org.stride.core.ui.profile.toUtcMillis

/** Garmin Connect web page where Personal API keys are created (docs/garmin/personal-api.md). */
const val GARMIN_KEY_URL = "https://connect.garmin.com/app/settings/personalApiToken"

val SETUP_STEPS = listOf(
    R.string.connect_step_1,
    R.string.connect_step_2,
    R.string.connect_step_3,
    R.string.connect_step_4,
    R.string.connect_step_5
)

/** Stateful entry for [org.stride.core.navigation.ConnectGarminRoute]. */
@Composable
fun ConnectGarminFlow(modifier: Modifier = Modifier, viewModel: ConnectViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    ConnectScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenGarmin = { runCatching { uriHandler.openUri(GARMIN_KEY_URL) } },
        onBack = { navigator.goBack() },
        modifier = modifier
    )
}

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onEvent: (ConnectEvent) -> Unit,
    onOpenGarmin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier, topBar = {
        StrideTopAppBar(title = stringResource(R.string.connect_title), onBack = onBack)
    }) { padding ->
        if (state.loadFailed) {
            ErrorState(
                title = stringResource(CoreUiR.string.load_error_title),
                message = stringResource(CoreUiR.string.load_error_message),
                onRetry = { onEvent(ConnectEvent.Retry) },
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .formInsets()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.keyRejected) RejectedBanner()
            if (state.keyLost && !state.connected) KeyLostBanner()
            if (state.connected) ConnectedCard(state, onEvent, onBack)
            // The key form shows its own errors; otherwise (e.g. a failed disconnect) show it here.
            val error = state.error
            if (error != null && !state.needsNewKey) {
                Text(error.message.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.needsNewKey) {
                SetupSteps(onOpenGarmin)
                KeyForm(state, onEvent)
            }
            Text(
                stringResource(R.string.connect_storage_note, stringResource(CoreUiR.string.garmin_attribution)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.showDisconnect) DisconnectDialog(state, onEvent)
    }
}

@Composable
private fun RejectedBanner() {
    StrideCard(title = stringResource(R.string.connect_rejected_title), trailing = {
        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
    }) {
        Text(
            stringResource(R.string.connect_rejected_text),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** The Keystore lost the key that protected saved secrets (AUD-027). */
@Composable
private fun KeyLostBanner() {
    StrideCard(title = stringResource(R.string.connect_key_lost_title), trailing = {
        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
    }) {
        Text(
            stringResource(R.string.connect_key_lost_body),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ConnectedCard(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit, onDone: () -> Unit) {
    val warn = state.expired || state.expiringSoon
    StrideCard(
        title = stringResource(R.string.connect_garmin_connect),
        trailing = {
            // The status is spelled out, never shown by colour alone (AUD-035).
            StatusPill(
                text = stringResource(
                    when {
                        state.expired -> R.string.connect_status_expired
                        state.expiringSoon -> R.string.connect_status_expiring_soon
                        else -> R.string.connect_status_connected
                    }
                ),
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    ) {
        val expiry = state.keyExpiry
        val days = state.daysLeft
        Text(
            when {
                expiry == null || days == null -> stringResource(R.string.connect_expiry_unset)
                state.expired -> stringResource(R.string.connect_expired_on, Format.dayWithYear(expiry))
                days == 0L -> stringResource(R.string.connect_expires_today)
                else -> pluralStringResource(R.plurals.connect_expires_on, days.toInt(), Format.dayWithYear(expiry), days.toInt())
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (warn && !state.expired) {
            Text(
                stringResource(R.string.connect_expiring_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrideOutlinedButton(text = stringResource(R.string.connect_disconnect), onClick = { onEvent(ConnectEvent.DisconnectClicked) })
            if (!state.needsNewKey) StrideButton(text = stringResource(R.string.connect_done), onClick = onDone)
        }
    }
}

@Composable
private fun SetupSteps(onOpenGarmin: () -> Unit) {
    StrideCard(title = stringResource(R.string.connect_steps_title)) {
        SETUP_STEPS.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.connect_step_number, index + 1),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        StrideOutlinedButton(
            text = stringResource(R.string.connect_open_garmin),
            onClick = onOpenGarmin,
            leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyForm(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit) {
    // rememberSaveable: an open date picker survives rotation and process death (AUD-033).
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    StrideCard(title = stringResource(if (state.connected) R.string.connect_paste_new_key else R.string.connect_paste_key)) {
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = { onEvent(ConnectEvent.KeyChanged(it)) },
            label = { Text(stringResource(R.string.connect_key_label)) },
            singleLine = true,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { onEvent(ConnectEvent.ToggleKeyVisible) }) {
                    Icon(
                        if (state.keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (state.keyVisible) CoreUiR.string.common_hide_key else CoreUiR.string.common_show_key
                        )
                    )
                }
            },
            isError = state.error != null,
            supportingText = state.error?.let { error -> { Text(error.message.resolve()) } },
            modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.connect_lifetime_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyLifetime.entries.forEach { lifetime ->
                FilterChip(
                    selected = state.lifetime == lifetime,
                    onClick = { onEvent(ConnectEvent.LifetimeChosen(lifetime)) },
                    label = { Text(stringResource(lifetime.labelRes)) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.connect_expires, Format.dayWithYear(state.expiry)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { pickingDate = true }) { Text(stringResource(CoreUiR.string.common_change)) }
        }
        StrideButton(
            text = stringResource(R.string.connect_connect),
            onClick = { onEvent(ConnectEvent.Connect) },
            loading = state.connecting,
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth()
        )
        if (pickingDate) {
            ExpiryPicker(
                initial = state.expiry,
                today = state.today,
                onPicked = {
                    onEvent(ConnectEvent.ExpiryPicked(it))
                    pickingDate = false
                },
                onDismiss = { pickingDate = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpiryPicker(initial: LocalDate, today: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = maxOf(initial, today).toUtcMillis(),
        yearRange = today.year..(today.year + MAX_KEY_YEARS),
        selectableDates = FutureDates(today)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcDate()) } ?: onDismiss() }) {
                Text(stringResource(CoreUiR.string.common_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun DisconnectDialog(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(ConnectEvent.DisconnectDismissed) },
        title = { Text(stringResource(R.string.connect_disconnect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connect_disconnect_text))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.deleteSyncedData,
                            role = Role.Checkbox,
                            onValueChange = { onEvent(ConnectEvent.DeleteSyncedDataChanged(it)) }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = state.deleteSyncedData, onCheckedChange = null)
                    Text(stringResource(R.string.connect_disconnect_delete_data), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(ConnectEvent.DisconnectConfirmed) }, enabled = !state.disconnecting) {
                Text(stringResource(R.string.connect_disconnect), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ConnectEvent.DisconnectDismissed) }) { Text(stringResource(CoreUiR.string.common_cancel)) }
        }
    )
}

@ThemePreviews
@Composable
private fun ConnectScreenPreview() {
    StrideTheme {
        ConnectScreen(
            state = ConnectUiState(loading = false, error = ConnectError.InvalidKey, keyInput = "abc"),
            onEvent = {},
            onOpenGarmin = {},
            onBack = {}
        )
    }
}

/** Garmin keys last at most a year; the picker offers a little headroom. */
private const val MAX_KEY_YEARS = 2

/** Key expiry dates: today or later only — a key cannot already have expired when you paste it (AUD-037). */
@OptIn(ExperimentalMaterial3Api::class)
internal class FutureDates(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcTimeMillis.toUtcDate().isBefore(today)

    override fun isSelectableYear(year: Int): Boolean = year >= today.year
}
