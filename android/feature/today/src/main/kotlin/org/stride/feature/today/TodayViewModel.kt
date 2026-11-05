package org.stride.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.data.insights.TodayInsights
import org.stride.core.data.insights.deltaFrom
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.data.repository.WellnessRepository
import org.stride.core.data.sync.SyncController
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DailyWellness

@HiltViewModel
class TodayViewModel @Inject constructor(
    insights: InsightsRepository,
    activities: ActivityRepository,
    wellness: WellnessRepository,
    prefs: UserPreferences,
    private val sync: SyncController
) : ViewModel() {
    val uiState: StateFlow<TodayUiState> = combine(
        insights.observeToday(),
        activities.observeActivities(),
        wellness.observeLatest(),
        prefs.settings,
        sync.progress
    ) { today, all, latestWellness, settings, progress ->
        TodayUiState(
            isLoading = false,
            content = todayContent(today, all, latestWellness),
            sync = progress,
            units = settings.unitSystem,
            demoMode = settings.demoMode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState())

    /** Pull-to-refresh. */
    fun refresh() = sync.syncNow()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Maps the computed insights to what Today shows; null when there is nothing to show yet. */
internal fun todayContent(today: TodayInsights, activities: List<ActivityWithMetrics>, wellness: DailyWellness?): TodayContent? {
    if (activities.isEmpty() && today.form == null) return null
    return TodayContent(
        today = today.today,
        form = today.form,
        band = today.band,
        acwr = today.acwr,
        acwrFlag = today.acwrFlag,
        lastRun = activities.filter { it.activity.sport.isRun }.maxByOrNull { it.activity.startEpochSec },
        thisWeek = today.thisWeek,
        lastWeek = today.lastWeek,
        weekDelta = today.thisWeek.deltaFrom(today.lastWeek),
        wellness = wellness,
        nextWorkout = today.nextWorkout
    )
}
