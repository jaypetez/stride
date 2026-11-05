package org.stride.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideLog
import org.stride.core.data.demo.DemoController
import org.stride.core.datastore.UserPreferences
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.science.coach.ReadinessScreening
import org.stride.core.science.coach.screenReadiness
import org.stride.core.ui.profile.ProfileField
import org.stride.core.ui.profile.ProfileFormState
import org.stride.core.ui.profile.validate

enum class OnboardingStep {
    WELCOME,
    PROFILE,
    PARQ,
    DISCLAIMER,
    FINISH
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val profile: ProfileFormState = ProfileFormState(),
    val profileErrors: Map<ProfileField, String> = emptyMap(),
    /** One answer per [PARQ_QUESTIONS]: true = yes, false = no, null = unanswered. */
    val parqAnswers: List<Boolean?> = List(PARQ_QUESTIONS.size) { null },
    val disclaimerAccepted: Boolean = false,
    val working: Boolean = false,
    val error: String? = null
) {
    val screening: ReadinessScreening get() = screenReadiness(parqAnswers.map { it == true })
    val parqComplete: Boolean get() = parqAnswers.none { it == null }
    val canContinue: Boolean
        get() = when (step) {
            OnboardingStep.PARQ -> parqComplete
            OnboardingStep.DISCLAIMER -> disclaimerAccepted
            else -> true
        }
}

sealed interface OnboardingEvent {
    data object Next : OnboardingEvent

    data object Back : OnboardingEvent

    data class ProfileChanged(val form: ProfileFormState) : OnboardingEvent

    data class ParqAnswered(val index: Int, val yes: Boolean) : OnboardingEvent

    data class DisclaimerAccepted(val accepted: Boolean) : OnboardingEvent

    data object ConnectGarmin : OnboardingEvent

    data object TryDemo : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val demo: DemoController,
    private val clock: StrideClock
) : ViewModel() {
    private val state = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    /**
     * Rendezvous: the destination is handed to the screen (which tells the shell
     * where to open) *before* the onboarding flag is persisted, because
     * persisting swaps the onboarding host for the shell.
     */
    private val finished = Channel<NavKey>(Channel.RENDEZVOUS)

    /** Where the shell should open once onboarding completes. */
    val destinations: Flow<NavKey> = finished.receiveAsFlow()

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            OnboardingEvent.Next -> next()

            OnboardingEvent.Back -> back()

            is OnboardingEvent.ProfileChanged -> state.update {
                // Re-validate live once errors are showing, so they clear as the user fixes them.
                val errors = if (it.profileErrors.isEmpty()) emptyMap() else event.form.validate(clock.today()).errors
                it.copy(profile = event.form, profileErrors = errors)
            }

            is OnboardingEvent.ParqAnswered -> state.update {
                it.copy(parqAnswers = it.parqAnswers.toMutableList().also { list -> list[event.index] = event.yes })
            }

            is OnboardingEvent.DisclaimerAccepted -> state.update { it.copy(disclaimerAccepted = event.accepted) }

            OnboardingEvent.ConnectGarmin -> finish(enterDemo = false)

            OnboardingEvent.TryDemo -> finish(enterDemo = true)
        }
    }

    /** Handles system back; false when already on the first step. */
    fun back(): Boolean {
        val current = state.value
        if (current.step == OnboardingStep.WELCOME || current.working) return false
        state.update { it.copy(step = OnboardingStep.entries[it.step.ordinal - 1], error = null) }
        return true
    }

    private fun next() {
        val current = state.value
        if (!current.canContinue) return
        if (current.step == OnboardingStep.PROFILE) {
            val validation = current.profile.validate(clock.today())
            if (!validation.isValid) {
                state.update { it.copy(profileErrors = validation.errors) }
                return
            }
        }
        if (current.step != OnboardingStep.FINISH) {
            state.update { it.copy(step = OnboardingStep.entries[it.step.ordinal + 1], profileErrors = emptyMap()) }
        }
    }

    private fun finish(enterDemo: Boolean) {
        val current = state.value
        if (current.working || !current.disclaimerAccepted) return
        val profile = current.profile.validate(clock.today()).profile ?: return
        state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                // Load the demo first so the shell never shows an empty Today screen.
                if (enterDemo) demo.enter()
                // Persist before handing off, and finish even if the screen goes away, so the app
                // never ends with demo on but onboarding incomplete (AUD-030).
                withContext(NonCancellable) { persist(profile, parqFlagged = !current.screening.cleared, rollbackDemo = enterDemo) }
                finished.send(if (enterDemo) TodayRoute else ConnectGarminRoute)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                StrideLog.w(TAG, "onboarding could not finish", e)
                state.update { s -> s.copy(working = false, error = "Couldn't finish setting up. Please try again.") }
            }
        }
    }

    /** Completes onboarding; if that fails after entering the demo, leaves demo mode again before rethrowing. */
    private suspend fun persist(profile: org.stride.core.model.AthleteProfile, parqFlagged: Boolean, rollbackDemo: Boolean) {
        try {
            prefs.completeOnboarding(profile, parqFlagged = parqFlagged)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (rollbackDemo) runCatching { demo.exit() }
            throw e
        }
    }

    private companion object {
        const val TAG = "Onboarding"
    }
}
