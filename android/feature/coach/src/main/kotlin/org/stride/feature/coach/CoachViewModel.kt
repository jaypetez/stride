package org.stride.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.stride.core.common.Retryable
import org.stride.core.common.StrideLog
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.ui.UiText

enum class CoachSubject { ACTIVITY, TREND }

data class CoachUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val caveatAccepted: Boolean = false,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    val activityId: Long? = null,
    val subject: CoachSubject = CoachSubject.TREND,
    val facts: CoachFacts? = null,
    val noData: Boolean = false,
    val asking: Boolean = false,
    val answer: CheckedReply? = null,
    val error: UiText? = null,
    /** Settings could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
)

sealed interface CoachEvent {
    data class CaveatAccepted(val accepted: Boolean) : CoachEvent

    data class KeyChanged(val key: String) : CoachEvent

    data object ToggleKeyVisible : CoachEvent

    data object Enable : CoachEvent

    data object Disable : CoachEvent

    data class SubjectChosen(val subject: CoachSubject) : CoachEvent

    data object Ask : CoachEvent

    data object Retry : CoachEvent
}

@HiltViewModel
class CoachViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val secrets: SecretStore,
    private val factsSource: CoachFactsSource,
    private val llm: CoachLlm
) : ViewModel() {
    private val state = MutableStateFlow(CoachUiState())
    val uiState: StateFlow<CoachUiState> = state.asStateFlow()
    private var factsJob: Job? = null

    private val retryable = Retryable()

    init {
        viewModelScope.launch {
            retryable.flow(onError = { null }) {
                combine(prefs.settings, secrets.hasAnthropicKey) { settings, hasKey -> settings.coachEnabled && hasKey }
            }.collect { enabled ->
                state.update {
                    if (enabled ==
                        null
                    ) {
                        it.copy(loading = false, loadFailed = true)
                    } else {
                        it.copy(loading = false, loadFailed = false, enabled = enabled)
                    }
                }
            }
        }
    }

    /** Called with the route's activity: the coach then explains that activity by default. */
    fun setActivity(activityId: Long?) {
        if (state.value.activityId == activityId && state.value.facts != null) return
        state.update {
            it.copy(activityId = activityId, subject = if (activityId != null) CoachSubject.ACTIVITY else CoachSubject.TREND, answer = null)
        }
        loadFacts()
    }

    fun onEvent(event: CoachEvent) {
        when (event) {
            is CoachEvent.CaveatAccepted -> state.update { it.copy(caveatAccepted = event.accepted) }

            is CoachEvent.KeyChanged -> state.update { it.copy(keyInput = event.key, error = null) }

            CoachEvent.ToggleKeyVisible -> state.update { it.copy(keyVisible = !it.keyVisible) }

            CoachEvent.Enable -> enable()

            CoachEvent.Disable -> viewModelScope.launch {
                // Turn the coach off first, so it stays off even if deleting the key fails.
                prefs.setCoachEnabled(false)
                secrets.clearAnthropicKey()
                state.update { it.copy(answer = null, caveatAccepted = false) }
            }

            is CoachEvent.SubjectChosen -> {
                state.update { it.copy(subject = event.subject, answer = null, error = null) }
                loadFacts()
            }

            CoachEvent.Ask -> ask()

            CoachEvent.Retry -> {
                state.update { it.copy(loading = true, loadFailed = false) }
                retryable.retry()
            }
        }
    }

    private fun enable() {
        val current = state.value
        val key = current.keyInput.trim()
        when {
            !current.caveatAccepted -> state.update { it.copy(error = UiText(R.string.coach_error_caveat)) }

            key.isEmpty() -> state.update { it.copy(error = UiText(R.string.coach_error_key_empty)) }

            else -> viewModelScope.launch {
                // Stored encrypted; never logged.
                secrets.saveAnthropicKey(key)
                prefs.setCoachEnabled(true)
                state.update { it.copy(keyInput = "", keyVisible = false, error = null) }
            }
        }
    }

    /** Facts for the latest subject only: a newer request cancels the one in flight (AUD-030). */
    private fun loadFacts() {
        val current = state.value
        factsJob?.cancel()
        factsJob = viewModelScope.launch {
            val facts = try {
                when (current.subject) {
                    CoachSubject.ACTIVITY -> current.activityId?.let { factsSource.activity(it) }
                    CoachSubject.TREND -> factsSource.trend()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                StrideLog.w(TAG, "coach facts unavailable", e)
                null
            }
            state.update { it.copy(facts = facts, noData = facts == null) }
        }
    }

    private fun ask() {
        val current = state.value
        val facts = current.facts
        if (current.asking || !current.enabled || facts == null) return
        state.update { it.copy(asking = true, error = null, answer = null) }
        viewModelScope.launch {
            val key = secrets.anthropicKey()
            if (key == null) {
                state.update { it.copy(asking = false, error = UiText(R.string.coach_error_key_missing)) }
                return@launch
            }
            when (val result = llm.complete(key, COACH_SYSTEM_PROMPT, coachUserPrompt(facts))) {
                is CoachLlmResult.Ok -> {
                    val checked = checkReplyNumbers(result.text, Json.parseToJsonElement(facts.toJson()))
                    state.update {
                        if (checked.rejected) {
                            it.copy(asking = false, error = UiText(R.string.coach_error_numbers))
                        } else {
                            it.copy(asking = false, answer = checked)
                        }
                    }
                }

                is CoachLlmResult.Err -> state.update { it.copy(asking = false, error = result.error.message) }
            }
        }
    }

    private companion object {
        const val TAG = "Coach"
    }
}
