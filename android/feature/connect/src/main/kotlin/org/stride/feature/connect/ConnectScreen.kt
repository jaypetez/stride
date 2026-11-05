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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
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
import org.stride.core.ui.GARMIN_ATTRIBUTION
import org.stride.core.ui.formInsets
import org.stride.core.ui.profile.toUtcDate
import org.stride.core.ui.profile.toUtcMillis

/** Garmin Connect web page where Personal API keys are created (docs/garmin/personal-api.md). */
const val GARMIN_KEY_URL = "https://connect.garmin.com/app/settings/personalApiToken"

val SETUP_STEPS = listOf(
    "Open Garmin Connect in your browser and sign in.",
    "Go to Settings, then Personal API.",
    "Create a key and choose how long it lasts: 30, 90 or 180 days, or 1 year.",
    "Copy the key straight away. Garmin shows it only once.",
    "Paste it below, check the expiry date and tap Connect."
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
    Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Garmin", onBack = onBack) }) { padding ->
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
                Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.needsNewKey) {
                SetupSteps(onOpenGarmin)
                KeyForm(state, onEvent)
            }
            Text(
                "Your key is stored encrypted on this phone and is only ever sent to Garmin. $GARMIN_ATTRIBUTION",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.showDisconnect) DisconnectDialog(state, onEvent)
    }
}

@Composable
private fun RejectedBanner() {
    StrideCard(title = "Garmin rejected your key", trailing = {
        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
    }) {
        Text(
            "Sync has stopped. The key may have expired or been deleted. Create a new key in Garmin Connect and paste it below.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** The Keystore lost the key that protected saved secrets (AUD-027). */
@Composable
private fun KeyLostBanner() {
    StrideCard(title = "Re-enter your Garmin key", trailing = {
        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
    }) {
        Text(
            "Stride could not unlock your saved key on this phone, so it was removed. Paste your Garmin key again to resume sync.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ConnectedCard(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit, onDone: () -> Unit) {
    val warn = state.expired || state.expiringSoon
    StrideCard(
        title = "Garmin Connect",
        trailing = {
            StatusPill(
                text = if (state.expired) "Expired" else "Connected",
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    ) {
        val expiry = state.keyExpiry
        val days = state.daysLeft
        Text(
            when {
                expiry == null || days == null -> "Key expiry not set."
                state.expired -> "Your key expired on ${Format.day(expiry)} ${expiry.year}."
                days == 0L -> "Your key expires today."
                else -> "Key expires on ${Format.day(expiry)} ${expiry.year} (in $days ${if (days == 1L) "day" else "days"})."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (warn && !state.expired) {
            Text(
                "Create a new key in Garmin Connect before it expires and paste it below to keep syncing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrideOutlinedButton(text = "Disconnect", onClick = { onEvent(ConnectEvent.DisconnectClicked) })
            if (!state.needsNewKey) StrideButton(text = "Done", onClick = onDone)
        }
    }
}

@Composable
private fun SetupSteps(onOpenGarmin: () -> Unit) {
    StrideCard(title = "Get your Garmin Personal API key") {
        SETUP_STEPS.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        StrideOutlinedButton(
            text = "Open Garmin Connect",
            onClick = onOpenGarmin,
            leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyForm(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    StrideCard(title = if (state.connected) "Paste a new key" else "Paste your key") {
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = { onEvent(ConnectEvent.KeyChanged(it)) },
            label = { Text("Personal API key") },
            singleLine = true,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { onEvent(ConnectEvent.ToggleKeyVisible) }) {
                    Icon(
                        if (state.keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.keyVisible) "Hide key" else "Show key"
                    )
                }
            },
            isError = state.error != null,
            supportingText = state.error?.let { error -> { Text(error.message) } },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Key lifetime you chose in Garmin Connect", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyLifetime.entries.forEach { lifetime ->
                FilterChip(
                    selected = state.lifetime == lifetime,
                    onClick = { onEvent(ConnectEvent.LifetimeChosen(lifetime)) },
                    label = { Text(lifetime.label) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Expires ${Format.day(state.expiry)} ${state.expiry.year}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { pickingDate = true }) { Text("Change") }
        }
        StrideButton(
            text = "Connect",
            onClick = { onEvent(ConnectEvent.Connect) },
            loading = state.connecting,
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth()
        )
        if (pickingDate) {
            ExpiryPicker(
                initial = state.expiry,
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
private fun ExpiryPicker(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toUtcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcDate()) } ?: onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun DisconnectDialog(state: ConnectUiState, onEvent: (ConnectEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(ConnectEvent.DisconnectDismissed) },
        title = { Text("Disconnect Garmin?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stride removes your key from this phone and stops syncing.")
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
                    Text("Also delete all synced data", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(ConnectEvent.DisconnectConfirmed) }, enabled = !state.disconnecting) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(ConnectEvent.DisconnectDismissed) }) { Text("Cancel") } }
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
