package org.stride.feature.today

import androidx.compose.runtime.Immutable
import org.stride.core.data.insights.VolumeDelta
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.data.sync.SyncProgress
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DailyWellness
import org.stride.core.model.FormBand
import org.stride.core.model.UnitSystem
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.WorkoutSuggestion

@Immutable
data class TodayUiState(
    val isLoading: Boolean = true,
    /** Null once loaded means there is no data yet (empty state). */
    val content: TodayContent? = null,
    val sync: SyncProgress = SyncProgress.Idle,
    val units: UnitSystem = UnitSystem.METRIC,
    val demoMode: Boolean = false,
    /** The data could not be read; Today shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
) {
    val isRefreshing: Boolean get() = sync is SyncProgress.Running
}

/** Everything on Today, already computed by core:data / core:science. */
@Immutable
data class TodayContent(
    val today: String,
    val form: DailyLoadPoint?,
    val band: FormBand?,
    val acwr: Double?,
    val acwrFlag: AcwrFlag?,
    val lastRun: ActivityWithMetrics?,
    val thisWeek: WeeklyVolume,
    val lastWeek: WeeklyVolume,
    val weekDelta: VolumeDelta,
    val wellness: DailyWellness?,
    val nextWorkout: WorkoutSuggestion?
)
