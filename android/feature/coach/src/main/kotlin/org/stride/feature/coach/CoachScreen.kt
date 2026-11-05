package org.stride.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideTextButton
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.LocalNavigator
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.ui.formInsets

/** Stateful entry for [org.stride.core.navigation.CoachRoute]. */
@Composable
fun CoachFlow(activityId: Long?, modifier: Modifier = Modifier, viewModel: CoachViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(viewModel, activityId) { viewModel.setActivity(activityId) }
    CoachScreen(state = state, onEvent = viewModel::onEvent, onBack = { navigator.goBack() }, modifier = modifier)
}

@Composable
fun CoachScreen(state: CoachUiState, onEvent: (CoachEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Coach", onBack = onBack) }) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding).fillMaxSize())
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
            if (state.enabled) CoachContent(state, onEvent) else CoachSetup(state, onEvent)
            Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoachSetup(state: CoachUiState, onEvent: (CoachEvent) -> Unit) {
    StrideCard(title = "Turn on the AI coach", subtitle = "Optional. Off by default. Uses your own Anthropic API key.") {
        Text(COACH_CAVEAT, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.caveatAccepted, role = Role.Checkbox, onValueChange = { onEvent(CoachEvent.CaveatAccepted(it)) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = state.caveatAccepted, onCheckedChange = null)
            Text("I have read this and want to use the coach", modifier = Modifier.padding(start = 12.dp))
        }
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = { onEvent(CoachEvent.KeyChanged(it)) },
            label = { Text("Anthropic API key") },
            singleLine = true,
            enabled = state.caveatAccepted,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { onEvent(CoachEvent.ToggleKeyVisible) }) {
                    Icon(
                        if (state.keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.keyVisible) "Hide key" else "Show key"
                    )
                }
            },
            supportingText = { Text(state.error ?: "Create one at console.anthropic.com. It is stored encrypted on this phone.") },
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth()
        )
        StrideButton(
            text = "Turn on coach",
            onClick = { onEvent(CoachEvent.Enable) },
            enabled = state.caveatAccepted,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CoachContent(state: CoachUiState, onEvent: (CoachEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.activityId != null) {
                FilterChip(
                    selected = state.subject == CoachSubject.ACTIVITY,
                    onClick = { onEvent(CoachEvent.SubjectChosen(CoachSubject.ACTIVITY)) },
                    label = { Text("This activity") }
                )
            }
            FilterChip(
                selected = state.subject == CoachSubject.TREND,
                onClick = { onEvent(CoachEvent.SubjectChosen(CoachSubject.TREND)) },
                label = { Text("My fitness trend") }
            )
        }
        val facts = state.facts
        if (facts == null) {
            if (state.noData) {
                EmptyState(title = "Nothing to explain yet", message = "Sync some runs (or try the demo) and ask again.")
            }
        } else {
            StrideCard(title = "What Stride sends", subtitle = "Only these computed numbers go to Anthropic.") {
                facts.describe().forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            StrideButton(
                text = if (state.subject == CoachSubject.ACTIVITY) "Explain this activity" else "Explain my fitness trend",
                onClick = { onEvent(CoachEvent.Ask) },
                loading = state.asking,
                enabled = !state.asking,
                leadingIcon = Icons.Outlined.AutoAwesome,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        state.answer?.let { answer ->
            StrideCard(title = "Coach") {
                Text(answer.text, style = MaterialTheme.typography.bodyLarge)
                if (answer.removed.isNotEmpty()) {
                    Text(
                        "${answer.removed.size} sentence(s) removed because they quoted numbers Stride didn't compute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        StrideTextButton(text = "Turn off coach and delete key", onClick = { onEvent(CoachEvent.Disable) })
    }
}

@ThemePreviews
@Composable
private fun CoachSetupPreview() {
    StrideTheme { CoachScreen(state = CoachUiState(loading = false), onEvent = {}, onBack = {}) }
}
