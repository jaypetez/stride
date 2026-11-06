package org.stride.feature.fitness

import androidx.annotation.StringRes
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
import org.stride.core.common.Retryable
import org.stride.core.data.insights.FitnessInsights
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.UnitSystem

/** PMC / ACWR time windows. */
enum class FitnessRange(@StringRes val labelRes: Int, val days: Long) {
    SIX_WEEKS(R.string.fitness_range_6_weeks, 42),
    THREE_MONTHS(R.string.fitness_range_3_months, 91),
    SIX_MONTHS(R.string.fitness_range_6_months, 182),
    ONE_YEAR(R.string.fitness_range_1_year, 365)
}

@Immutable
data class FitnessUiState(
    val isLoading: Boolean = true,
    val range: FitnessRange = FitnessRange.THREE_MONTHS,
    /** The PMC points inside [range] (oldest first). */
    val window: List<DailyLoadPoint> = emptyList(),
    /** Everything else on the screen, unfiltered. Null before the first load. */
    val insights: FitnessInsights? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    /** The insights could not be computed; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false,
    /** Runs are stored (MVP-024): an empty PMC then means "still calculating", not "no data". */
    val hasActivities: Boolean = false,
    /** The Garmin history backfill is still running; older runs are yet to arrive (MVP-024). */
    val importingHistory: Boolean = false
) {
    private val noPmc: Boolean get() = !isLoading && insights?.pmc.isNullOrEmpty()

    /** Nothing to show and no runs either: the "No fitness data yet" state. */
    val isEmpty: Boolean get() = noPmc && !hasActivities

    /** Runs exist but their daily loads have not been computed yet (a sync in flight). */
    val isCalculating: Boolean get() = noPmc && hasActivities

    /** Show the "still importing your history" note above the charts. */
    val showImportingNote: Boolean get() = importingHistory && !noPmc && !isLoading
}

@HiltViewModel
class FitnessViewModel @Inject constructor(insights: InsightsRepository, prefs: UserPreferences) : ViewModel() {
    private val range = MutableStateFlow(FitnessRange.THREE_MONTHS)

    private val retryable = Retryable()

    val uiState: StateFlow<FitnessUiState> = retryable.flow(onError = { FitnessUiState(isLoading = false, loadFailed = true) }) {
        combine(insights.observeFitness(), insights.observeImportProgress(), prefs.settings, range) { fitness, import, settings, r ->
            FitnessUiState(
                isLoading = false,
                range = r,
                window = pmcWindow(fitness.pmc, r),
                insights = fitness,
                units = settings.unitSystem,
                hasActivities = import.hasActivities,
                importingHistory = import.backfillInProgress
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FitnessUiState())

    fun retry() = retryable.retry()

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
