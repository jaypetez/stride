package org.stride.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.science.coach.ReadinessScreening
import org.stride.core.science.coach.screenReadiness
import org.stride.core.ui.UiText
import org.stride.core.ui.profile.ProfileField
import org.stride.core.ui.profile.ProfileFormState
import org.stride.core.ui.profile.RaceDistance
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
    val profileErrors: Map<ProfileField, UiText> = emptyMap(),
    /** One answer per [PARQ_QUESTIONS]: true = yes, false = no, null = unanswered. */
    val parqAnswers: List<Boolean?> = List(PARQ_QUESTIONS.size) { null },
    val disclaimerAccepted: Boolean = false,
    val working: Boolean = false,
    val error: UiText? = null
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
    private val clock: StrideClock,
    private val savedState: SavedStateHandle
) : ViewModel() {
    /**
     * Backed by [SavedStateHandle] (AUD-033): users who leave mid health check come
     * back to the same step with their profile and PAR-Q answers intact, even after
     * the process was killed. Validation errors and in-flight work are not restored.
     */
    private val state = MutableStateFlow(savedState.restoreOnboarding())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { state.collect { savedState.saveOnboarding(it) } }
    }

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
                // Load the demo first so the shell never shows an empty Today screen, then persist
                // before handing off. Both run as one non-cancellable unit: DemoMode.enter() writes
                // demoMode=true as its last step, so if the screen went away mid-load a cancellation
                // there would leave demo on but onboarding incomplete (AUD-030).
                withContext(NonCancellable) {
                    if (enterDemo) demo.enter()
                    persist(profile, parqFlagged = !current.screening.cleared, rollbackDemo = enterDemo)
                }
                finished.send(if (enterDemo) TodayRoute else ConnectGarminRoute)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                StrideLog.w(TAG, "onboarding could not finish", e)
                state.update { s -> s.copy(working = false, error = UiText(R.string.onboarding_error_finish)) }
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

private const val KEY_STEP = "onboarding.step"
private const val KEY_NAME = "onboarding.name"
private const val KEY_BIRTH_YEAR = "onboarding.birthYear"
private const val KEY_SEX = "onboarding.sex"
private const val KEY_UNITS = "onboarding.units"
private const val KEY_MAX_HR = "onboarding.maxHr"
private const val KEY_RESTING_HR = "onboarding.restingHr"
private const val KEY_THRESHOLD_HR = "onboarding.thresholdHr"
private const val KEY_RACE_DISTANCE = "onboarding.raceDistance"
private const val KEY_RACE_DATE = "onboarding.raceDate"
private const val KEY_PARQ = "onboarding.parq"
private const val KEY_DISCLAIMER = "onboarding.disclaimer"

/** Bundle-safe values only (strings, booleans, an int array) so the state survives process death. */
internal fun SavedStateHandle.saveOnboarding(state: OnboardingUiState) {
    val p = state.profile
    this[KEY_STEP] = state.step.name
    this[KEY_NAME] = p.name
    this[KEY_BIRTH_YEAR] = p.birthYear
    this[KEY_SEX] = p.sex.name
    this[KEY_UNITS] = p.units.name
    this[KEY_MAX_HR] = p.maxHr
    this[KEY_RESTING_HR] = p.restingHr
    this[KEY_THRESHOLD_HR] = p.thresholdHr
    this[KEY_RACE_DISTANCE] = p.raceDistance?.name
    this[KEY_RACE_DATE] = p.raceDate?.toString()
    // -1 unanswered, 0 no, 1 yes.
    this[KEY_PARQ] = state.parqAnswers.map { answer ->
        if (answer == null) {
            -1
        } else if (answer) {
            1
        } else {
            0
        }
    }.toIntArray()
    this[KEY_DISCLAIMER] = state.disclaimerAccepted
}

internal fun SavedStateHandle.restoreOnboarding(): OnboardingUiState {
    val defaults = OnboardingUiState()
    fun <E : Enum<E>> enum(key: String, values: Array<E>, fallback: E): E =
        get<String>(key)?.let { n -> values.firstOrNull { it.name == n } } ?: fallback
    val profile = ProfileFormState(
        name = get<String>(KEY_NAME).orEmpty(),
        birthYear = get<String>(KEY_BIRTH_YEAR).orEmpty(),
        sex = enum(KEY_SEX, Sex.entries.toTypedArray(), defaults.profile.sex),
        units = enum(KEY_UNITS, UnitSystem.entries.toTypedArray(), defaults.profile.units),
        maxHr = get<String>(KEY_MAX_HR).orEmpty(),
        restingHr = get<String>(KEY_RESTING_HR).orEmpty(),
        thresholdHr = get<String>(KEY_THRESHOLD_HR).orEmpty(),
        raceDistance = get<String>(KEY_RACE_DISTANCE)?.let { n -> RaceDistance.entries.firstOrNull { it.name == n } },
        raceDate = get<String>(KEY_RACE_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    )
    val parq = get<IntArray>(KEY_PARQ)?.takeIf { it.size == PARQ_QUESTIONS.size }
        ?.map { if (it < 0) null else it == 1 } ?: defaults.parqAnswers
    return defaults.copy(
        step = enum(KEY_STEP, OnboardingStep.entries.toTypedArray(), defaults.step),
        profile = profile,
        parqAnswers = parq,
        disclaimerAccepted = get<Boolean>(KEY_DISCLAIMER) ?: false
    )
}
