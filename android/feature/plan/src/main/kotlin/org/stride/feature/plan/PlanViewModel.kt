package org.stride.feature.plan

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.stride.core.common.StrideClock
import org.stride.core.data.insights.dates
import org.stride.core.data.plan.PlanRepository
import org.stride.core.data.plan.PlanRequest
import org.stride.core.data.plan.PlanResult
import org.stride.core.data.plan.earliestRaceDate
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.UnitSystem
import org.stride.core.science.model.GuardrailViolation
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.TrainingPlan

/** The "create a plan" form. */
@Immutable
data class PlanForm(val distance: RaceDistance = RaceDistance.TEN_K, val raceDate: LocalDate, val goalTime: String = "")

@Immutable
data class PlanUiState(
    val isLoading: Boolean = true,
    val today: String,
    /** Only plans that passed `validatePlan` ever reach the UI (PlanRepository re-checks on read). */
    val plan: TrainingPlan? = null,
    /** Index into plan.weeks of the week containing [today], if any. */
    val currentWeekIndex: Int? = null,
    val form: PlanForm,
    /** The earliest race date the form accepts (plans start next Monday). */
    val earliestRaceDate: LocalDate,
    val creating: Boolean = false,
    /** Guardrail violations of a rejected plan (nothing was saved). */
    val violations: List<GuardrailViolation> = emptyList(),
    /** Fixes repairPlan applied to the saved plan. */
    val repairs: List<GuardrailViolation> = emptyList(),
    val error: String? = null,
    val units: UnitSystem = UnitSystem.METRIC
)

private data class Transient(
    val creating: Boolean = false,
    val violations: List<GuardrailViolation> = emptyList(),
    val repairs: List<GuardrailViolation> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val plans: PlanRepository,
    private val prefs: UserPreferences,
    clock: StrideClock
) : ViewModel() {
    private val today = clock.today()
    private val form = MutableStateFlow(PlanForm(raceDate = today.plusWeeks(DEFAULT_WEEKS_OUT)))
    private val transient = MutableStateFlow(Transient())
    private val initial = PlanUiState(today = today.toString(), form = form.value, earliestRaceDate = earliestRaceDate(today))

    init {
        viewModelScope.launch {
            prefs.settings.first().profile.raceGoal?.let { goal ->
                val date = runCatching { LocalDate.parse(goal.date) }.getOrNull()
                form.update { f ->
                    f.copy(
                        distance = raceDistanceFor(goal.distanceM) ?: f.distance,
                        raceDate = date?.takeIf { !it.isBefore(initial.earliestRaceDate) } ?: f.raceDate,
                        goalTime = goal.targetTimeSec?.let(::formatGoalTime).orEmpty()
                    )
                }
            }
        }
    }

    // The date ticker moves "today" and the current week on at midnight while the screen is open (AUD-031).
    val uiState: StateFlow<PlanUiState> = combine(plans.plan, prefs.settings, form, transient, clock.dates()) { plan, settings, f, t, day ->
        val todayKey = day.toString()
        initial.copy(
            isLoading = false,
            today = todayKey,
            earliestRaceDate = planStartDate(day),
            plan = plan,
            currentWeekIndex = plan?.weeks?.indexOfFirst { w -> w.days.any { it.date == todayKey } }?.takeIf { it >= 0 },
            form = f,
            creating = t.creating,
            violations = t.violations,
            repairs = t.repairs,
            error = t.error,
            units = settings.unitSystem
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun setDistance(distance: RaceDistance) = form.update { it.copy(distance = distance) }

    fun setRaceDate(date: LocalDate) = form.update { it.copy(raceDate = date) }

    fun setGoalTime(text: String) = form.update { it.copy(goalTime = text) }

    fun create() {
        val f = form.value
        val goal = if (f.goalTime.isBlank()) null else parseGoalTime(f.goalTime)
        if (f.goalTime.isNotBlank() && goal == null) {
            transient.value = Transient(error = "Enter the goal time as h:mm:ss or mm:ss.")
            return
        }
        viewModelScope.launch {
            transient.value = Transient(creating = true)
            transient.value = when (val result = plans.create(PlanRequest(f.distance, f.raceDate, goal))) {
                is PlanResult.Created -> Transient(repairs = result.repairs)
                is PlanResult.Rejected -> Transient(violations = result.violations)
                is PlanResult.Invalid -> Transient(error = result.message)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            plans.delete()
            transient.value = Transient()
        }
    }

    private companion object {
        const val DEFAULT_WEEKS_OUT = 12L
    }
}

/** "1:45:00" / "45:30" / "45" (minutes) → seconds; null when unparseable or not positive. */
internal fun parseGoalTime(text: String): Double? {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
    if (numbers.any { it < 0 } || numbers.drop(1).any { it >= 60 }) return null
    val seconds = when (numbers.size) {
        1 -> numbers[0] * 60
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
    return seconds.toDouble().takeIf { it > 0 }
}

internal fun formatGoalTime(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, (total % 3600) / 60, total % 60)
}

/** The standard race closest to [metres] (within 2 %), if any. */
internal fun raceDistanceFor(metres: Double): RaceDistance? = listOf(
    RaceDistance.FIVE_K to 5_000.0,
    RaceDistance.TEN_K to 10_000.0,
    RaceDistance.HALF to 21_097.5,
    RaceDistance.MARATHON to 42_195.0
).firstOrNull { (_, m) -> abs(m - metres) / m < 0.02 }?.first
