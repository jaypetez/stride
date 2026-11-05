package org.stride.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideOutlinedButton
import org.stride.core.designsystem.component.StrideTextButton
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.LocalNavigator
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.ui.PRIVACY_NOTE
import org.stride.core.ui.formInsets
import org.stride.core.ui.fullScreenInsets
import org.stride.core.ui.profile.ProfileForm

const val PARQ_GUIDANCE =
    "You answered yes to at least one question. Please talk to a doctor or another qualified health professional " +
        "before you start training or make it harder. You can still use Stride to look at your data."

/** Stateful entry: wires [OnboardingViewModel] to [OnboardingScreen] and hands off to the shell when done. */
@Composable
fun OnboardingFlow(modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.destinations.collect { navigator.resetTo(it) }
    }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME && !state.working) { viewModel.back() }
    OnboardingScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** Stateless onboarding: one step at a time with a progress bar and Back / Continue. */
@Composable
fun OnboardingScreen(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit, modifier: Modifier = Modifier) {
    // testTagsAsResourceId lets the Maestro smoke flow target controls by id (android/maestro/demo-smoke.yaml).
    Surface(
        modifier = modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().fullScreenInsets().formInsets()) {
            LinearProgressIndicator(
                progress = { (state.step.ordinal + 1f) / OnboardingStep.entries.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.PROFILE -> ProfileStep(state, onEvent)
                    OnboardingStep.PARQ -> ParqStep(state, onEvent)
                    OnboardingStep.DISCLAIMER -> DisclaimerStep(state, onEvent)
                    OnboardingStep.FINISH -> FinishStep(state, onEvent)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (state.step != OnboardingStep.FINISH) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.step != OnboardingStep.WELCOME) StrideTextButton("Back", onClick = { onEvent(OnboardingEvent.Back) })
                    Spacer(Modifier.weight(1f))
                    StrideButton(
                        text = if (state.step == OnboardingStep.WELCOME) "Get started" else "Continue",
                        onClick = { onEvent(OnboardingEvent.Next) },
                        enabled = state.canContinue
                    )
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WelcomeStep() {
    Icon(Icons.AutoMirrored.Outlined.DirectionsRun, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    StepTitle(
        "Welcome to Stride",
        "Your running data, understood. Stride computes your training load, fitness, form and zones on this phone " +
            "from your own Garmin data."
    )
    StrideCard(title = "Private by design") {
        Text(PRIVACY_NOTE, style = MaterialTheme.typography.bodyMedium)
        Text("There is no Stride account and no server.", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "Setup takes about two minutes: a short profile, a health check and a disclaimer.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProfileStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle("About you", "Stride uses this to set your heart-rate zones and training load. It stays on this phone.")
    ProfileForm(
        state = state.profile,
        onChange = { onEvent(OnboardingEvent.ProfileChanged(it)) },
        errors = state.profileErrors
    )
}

@Composable
private fun ParqStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle("Health check", "Answer each question honestly (PAR-Q). Your answers stay on this phone.")
    PARQ_QUESTIONS.forEachIndexed { index, question ->
        StrideCard {
            Text(question, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.parqAnswers[index] == true,
                    onClick = { onEvent(OnboardingEvent.ParqAnswered(index, yes = true)) },
                    label = { Text("Yes") },
                    modifier = Modifier.testTag("parq_yes_$index")
                )
                FilterChip(
                    selected = state.parqAnswers[index] == false,
                    onClick = { onEvent(OnboardingEvent.ParqAnswered(index, yes = false)) },
                    label = { Text("No") },
                    modifier = Modifier.testTag("parq_no_$index")
                )
            }
        }
    }
    val screening = state.screening
    if (!screening.cleared) {
        StrideCard(title = "See a professional first") {
            Text(PARQ_GUIDANCE, style = MaterialTheme.typography.bodyMedium)
            Text(
                "You flagged: ${screening.healthFlags.joinToString(", ")}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DisclaimerStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle("Before you start")
    StrideCard {
        Text(DISCLAIMER, style = MaterialTheme.typography.bodyLarge)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.disclaimerAccepted,
                role = Role.Checkbox,
                onValueChange = { onEvent(OnboardingEvent.DisclaimerAccepted(it)) }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = state.disclaimerAccepted, onCheckedChange = null)
        Text("I have read and accept this", modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun FinishStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle(
        "You're all set${state.profile.name.trim().takeIf {
            it.isNotEmpty()
        }?.let { ", $it" }.orEmpty()}",
        "Where should your runs come from?"
    )
    StrideCard(title = "Connect Garmin", subtitle = "Use your own Garmin Personal API key. Runs and health data sync to this phone.") {
        StrideButton(
            text = "Connect Garmin",
            onClick = { onEvent(OnboardingEvent.ConnectGarmin) },
            enabled = !state.working,
            loading = state.working,
            leadingIcon = Icons.Outlined.Link,
            modifier = Modifier.fillMaxWidth()
        )
    }
    StrideCard(title = "Try the demo", subtitle = "Explore Stride with synthetic runs. You can connect Garmin later in You → Garmin.") {
        StrideOutlinedButton(
            text = "Try demo",
            onClick = { onEvent(OnboardingEvent.TryDemo) },
            enabled = !state.working,
            leadingIcon = Icons.Outlined.Science,
            modifier = Modifier.fillMaxWidth()
        )
    }
    StrideTextButton("Back", onClick = { onEvent(OnboardingEvent.Back) }, enabled = !state.working)
}

@ThemePreviews
@Composable
private fun OnboardingParqPreview() {
    StrideTheme {
        OnboardingScreen(
            state = OnboardingUiState(step = OnboardingStep.PARQ, parqAnswers = List(PARQ_QUESTIONS.size) { it == 1 }),
            onEvent = {}
        )
    }
}
