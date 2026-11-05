package org.stride.feature.activities

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.UnitSystem

@Immutable
data class ActivitiesUiState(
    val isLoading: Boolean = true,
    /** Newest month first; activities newest first within a month. */
    val months: List<MonthGroup> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC
)

@Immutable
data class MonthGroup(val key: String, val title: String, val activities: List<ActivityWithMetrics>)

@HiltViewModel
class ActivitiesViewModel @Inject constructor(activities: ActivityRepository, prefs: UserPreferences) : ViewModel() {
    val uiState: StateFlow<ActivitiesUiState> = combine(activities.observeActivities(), prefs.settings) { list, settings ->
        ActivitiesUiState(isLoading = false, months = groupByMonth(list), units = settings.unitSystem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesUiState())
}

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)

/** Groups by the month the activity happened in (its own time zone), newest first. */
internal fun groupByMonth(activities: List<ActivityWithMetrics>): List<MonthGroup> = activities
    .sortedByDescending { it.activity.startEpochSec }
    .groupBy { a ->
        val zone = a.activity.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        YearMonth.from(Instant.ofEpochSecond(a.activity.startEpochSec).atZone(zone))
    }
    .map { (month, list) -> MonthGroup(month.toString(), MONTH_TITLE.format(month), list) }
