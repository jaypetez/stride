package org.stride.feature.activities

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stride.core.common.Retryable
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.UnitSystem

@Immutable
data class ActivitiesUiState(
    val isLoading: Boolean = true,
    /** Newest month first; activities newest first within a month. */
    val months: List<MonthGroup> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    /** The runs could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
)

/** One month of runs; [month] is formatted for the device locale by the screen. */
@Immutable
data class MonthGroup(val month: YearMonth, val activities: List<ActivityWithMetrics>) {
    val key: String get() = month.toString()
}

@HiltViewModel
class ActivitiesViewModel @Inject constructor(activities: ActivityRepository, prefs: UserPreferences) : ViewModel() {
    private val retryable = Retryable()

    val uiState: StateFlow<ActivitiesUiState> = retryable.flow(onError = { ActivitiesUiState(isLoading = false, loadFailed = true) }) {
        combine(activities.observeActivities(), prefs.settings) { list, settings ->
            ActivitiesUiState(isLoading = false, months = groupByMonth(list), units = settings.unitSystem)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesUiState())

    fun retry() = retryable.retry()
}

/** Groups by the month the activity happened in (its own time zone), newest first. */
internal fun groupByMonth(activities: List<ActivityWithMetrics>): List<MonthGroup> = activities
    .sortedByDescending { it.activity.startEpochSec }
    .groupBy { a ->
        val zone = a.activity.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        YearMonth.from(Instant.ofEpochSecond(a.activity.startEpochSec).atZone(zone))
    }
    .map { (month, list) -> MonthGroup(month, list) }
