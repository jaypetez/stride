package org.stride.feature.fitness

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stride.core.data.insights.FitnessInsights
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.UnitSystem

/** PMC / ACWR time windows. */
enum class FitnessRange(val label: String, val days: Long) {
    SIX_WEEKS("6 weeks", 42),
    THREE_MONTHS("3 months", 91),
    SIX_MONTHS("6 months", 182),
    ONE_YEAR("1 year", 365)
}

@Immutable
data class FitnessUiState(
    val isLoading: Boolean = true,
    val range: FitnessRange = FitnessRange.THREE_MONTHS,
    /** The PMC points inside [range] (oldest first). */
    val window: List<DailyLoadPoint> = emptyList(),
    /** Everything else on the screen, unfiltered. Null before the first load. */
    val insights: FitnessInsights? = null,
    val units: UnitSystem = UnitSystem.METRIC
) {
    val isEmpty: Boolean get() = !isLoading && insights?.pmc.isNullOrEmpty()
}

@HiltViewModel
class FitnessViewModel @Inject constructor(insights: InsightsRepository, prefs: UserPreferences) : ViewModel() {
    private val range = MutableStateFlow(FitnessRange.THREE_MONTHS)

    val uiState: StateFlow<FitnessUiState> = combine(insights.observeFitness(), prefs.settings, range) { fitness, settings, r ->
        FitnessUiState(isLoading = false, range = r, window = pmcWindow(fitness.pmc, r), insights = fitness, units = settings.unitSystem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FitnessUiState())

    fun selectRange(newRange: FitnessRange) {
        range.value = newRange
    }
}

/** The last [range] days of [pmc], ending at its newest point. */
internal fun pmcWindow(pmc: List<DailyLoadPoint>, range: FitnessRange): List<DailyLoadPoint> {
    val last = pmc.lastOrNull() ?: return emptyList()
    val from = LocalDate.parse(last.date).minusDays(range.days - 1).toString()
    return pmc.filter { it.date >= from }
}
