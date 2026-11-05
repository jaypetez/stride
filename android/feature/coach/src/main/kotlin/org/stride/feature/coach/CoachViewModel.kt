package org.stride.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences

/** The Garmin-terms caveat shown before the coach can be enabled (ADR 0010, docs/garmin/personal-api.md). */
const val COACH_CAVEAT =
    "Garmin's developer terms reportedly bar using Garmin data to \"create or maintain any AI model, AI system or AI generated " +
        "knowledge base\". The coach is optional and off by default. If you turn it on, Stride sends only numbers it has already " +
        "computed (training load, fitness, fatigue and form, heart-rate zone minutes, VDOT, efficiency and weekly volume) to " +
        "Anthropic, using your own API key. It never sends GPS, streams, laps, FIT files or raw Garmin data, and nothing is used " +
        "to train a model. Decide for yourself whether this fits Garmin's terms."

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
    val error: String? = null
)

sealed interface CoachEvent {
    data class CaveatAccepted(val accepted: Boolean) : CoachEvent

    data class KeyChanged(val key: String) : CoachEvent

    data object ToggleKeyVisible : CoachEvent

    data object Enable : CoachEvent

    data object Disable : CoachEvent

    data class SubjectChosen(val subject: CoachSubject) : CoachEvent

    data object Ask : CoachEvent
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

    init {
        viewModelScope.launch {
            combine(prefs.settings, secrets.hasAnthropicKey) { settings, hasKey -> settings.coachEnabled && hasKey }
                .collect { enabled -> state.update { it.copy(loading = false, enabled = enabled) } }
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
                secrets.clearAnthropicKey()
                prefs.setCoachEnabled(false)
                state.update { it.copy(answer = null, caveatAccepted = false) }
            }

            is CoachEvent.SubjectChosen -> {
                state.update { it.copy(subject = event.subject, answer = null, error = null) }
                loadFacts()
            }

            CoachEvent.Ask -> ask()
        }
    }

    private fun enable() {
        val current = state.value
        val key = current.keyInput.trim()
        when {
            !current.caveatAccepted -> state.update { it.copy(error = "Read and accept the note above first.") }

            key.isEmpty() -> state.update { it.copy(error = "Paste your Anthropic API key.") }

            else -> viewModelScope.launch {
                // Stored encrypted; never logged.
                secrets.saveAnthropicKey(key)
                prefs.setCoachEnabled(true)
                state.update { it.copy(keyInput = "", keyVisible = false, error = null) }
            }
        }
    }

    private fun loadFacts() {
        val current = state.value
        viewModelScope.launch {
            val facts = runCatching {
                when (current.subject) {
                    CoachSubject.ACTIVITY -> current.activityId?.let { factsSource.activity(it) }
                    CoachSubject.TREND -> factsSource.trend()
                }
            }.getOrNull()
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
                state.update { it.copy(asking = false, error = "Add your Anthropic API key again.") }
                return@launch
            }
            when (val result = llm.complete(key, COACH_SYSTEM_PROMPT, coachUserPrompt(facts))) {
                is CoachLlmResult.Ok -> {
                    val checked = checkReplyNumbers(result.text, Json.parseToJsonElement(facts.toJson()))
                    state.update {
                        if (checked.rejected) {
                            it.copy(
                                asking = false,
                                error = "The coach's answer quoted numbers Stride didn't compute, so it was hidden. Try asking again."
                            )
                        } else {
                            it.copy(asking = false, answer = checked)
                        }
                    }
                }

                is CoachLlmResult.Err -> state.update { it.copy(asking = false, error = result.error.message) }
            }
        }
    }
}
