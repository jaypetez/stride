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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.formInsets
import org.stride.core.ui.fullScreenInsets
import org.stride.core.ui.profile.ProfileForm

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
                state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
            if (state.step != OnboardingStep.FINISH) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.step != OnboardingStep.WELCOME) {
                        StrideTextButton(stringResource(R.string.onboarding_back), onClick = { onEvent(OnboardingEvent.Back) })
                    }
                    Spacer(Modifier.weight(1f))
                    StrideButton(
                        text = stringResource(
                            if (state.step ==
                                OnboardingStep.WELCOME
                            ) {
                                R.string.onboarding_get_started
                            } else {
                                R.string.onboarding_continue
                            }
                        ),
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
    StepTitle(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_subtitle))
    StrideCard(title = stringResource(R.string.onboarding_private_title)) {
        Text(stringResource(CoreUiR.string.privacy_note), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.onboarding_no_account), style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        stringResource(R.string.onboarding_setup_time),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProfileStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle(stringResource(R.string.onboarding_about_title), stringResource(R.string.onboarding_about_subtitle))
    ProfileForm(
        state = state.profile,
        onChange = { onEvent(OnboardingEvent.ProfileChanged(it)) },
        errors = state.profileErrors
    )
}

@Composable
private fun ParqStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle(stringResource(R.string.onboarding_parq_title), stringResource(R.string.onboarding_parq_subtitle))
    val yes = stringResource(R.string.onboarding_yes)
    val no = stringResource(R.string.onboarding_no)
    PARQ_QUESTIONS.forEachIndexed { index, question ->
        StrideCard {
            Text(question, style = MaterialTheme.typography.bodyLarge)
            // AUD-035: a radio group whose options carry the question, so TalkBack reads
            // "Yes, <question>, selected" rather than a bare "Yes".
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                ParqOption(yes, question, state.parqAnswers[index] == true, "parq_yes_$index") {
                    onEvent(OnboardingEvent.ParqAnswered(index, yes = true))
                }
                ParqOption(no, question, state.parqAnswers[index] == false, "parq_no_$index") {
                    onEvent(OnboardingEvent.ParqAnswered(index, yes = false))
                }
            }
        }
    }
    val screening = state.screening
    if (!screening.cleared) {
        StrideCard(title = stringResource(R.string.onboarding_parq_flag_title)) {
            Text(stringResource(R.string.onboarding_parq_guidance), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.onboarding_parq_flagged, screening.healthFlags.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ParqOption(answer: String, question: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val description = stringResource(R.string.onboarding_parq_answer, answer, question)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(answer) },
        modifier = Modifier
            .testTag(tag)
            .semantics {
                contentDescription = description
                role = Role.RadioButton
            }
    )
}

@Composable
private fun DisclaimerStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    StepTitle(stringResource(R.string.onboarding_disclaimer_title))
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
        Text(stringResource(R.string.onboarding_disclaimer_accept), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun FinishStep(state: OnboardingUiState, onEvent: (OnboardingEvent) -> Unit) {
    val name = state.profile.name.trim()
    StepTitle(
        if (name.isEmpty()) stringResource(R.string.onboarding_all_set) else stringResource(R.string.onboarding_all_set_name, name),
        stringResource(R.string.onboarding_source_question)
    )
    StrideCard(
        title = stringResource(R.string.onboarding_connect_garmin),
        subtitle = stringResource(R.string.onboarding_connect_subtitle)
    ) {
        StrideButton(
            text = stringResource(R.string.onboarding_connect_garmin),
            onClick = { onEvent(OnboardingEvent.ConnectGarmin) },
            enabled = !state.working,
            loading = state.working,
            leadingIcon = Icons.Outlined.Link,
            modifier = Modifier.fillMaxWidth()
        )
    }
    StrideCard(title = stringResource(R.string.onboarding_demo_title), subtitle = stringResource(R.string.onboarding_demo_subtitle)) {
        StrideOutlinedButton(
            text = stringResource(R.string.onboarding_try_demo),
            onClick = { onEvent(OnboardingEvent.TryDemo) },
            enabled = !state.working,
            leadingIcon = Icons.Outlined.Science,
            modifier = Modifier.fillMaxWidth()
        )
    }
    StrideTextButton(stringResource(R.string.onboarding_back), onClick = { onEvent(OnboardingEvent.Back) }, enabled = !state.working)
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
