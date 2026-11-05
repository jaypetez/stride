package org.stride.feature.activities

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stride.core.common.Retryable
import org.stride.core.data.insights.paceSecPerKm
import org.stride.core.data.insights.zoneShares
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.datastore.UserPreferences
import org.stride.core.designsystem.chart.ChartMath
import org.stride.core.model.ActivityDetail
import org.stride.core.model.ActivityStreams
import org.stride.core.model.UnitSystem

@Immutable
data class ActivityDetailUiState(
    val isLoading: Boolean = true,
    /** Null once loaded means the activity no longer exists. */
    val detail: ActivityDetail? = null,
    val charts: StreamCharts? = null,
    /** Share of HR-zone time (0..1) per zone 1..5; empty without heart rate. */
    val zoneShares: List<Double> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    /** The run could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
)

/** Streams reduced to at most [MAX_CHART_POINTS] points for drawing; NaN = gap. */
@Immutable
data class StreamCharts(
    /** Elapsed seconds at the start of each drawn point. */
    val timeSec: List<Double>,
    val heartRate: List<Double>?,
    /** Seconds per km. */
    val paceSecPerKm: List<Double>?
)

const val MAX_CHART_POINTS = 240

@HiltViewModel(assistedFactory = ActivityDetailViewModel.Factory::class)
class ActivityDetailViewModel @AssistedInject constructor(
    @Assisted val activityId: Long,
    activities: ActivityRepository,
    prefs: UserPreferences
) : ViewModel() {
    private val retryable = Retryable()

    val uiState: StateFlow<ActivityDetailUiState> = retryable.flow(onError = {
        ActivityDetailUiState(isLoading = false, loadFailed = true)
    }) {
        combine(activities.observeDetail(activityId), prefs.settings) { detail, settings ->
            ActivityDetailUiState(
                isLoading = false,
                detail = detail,
                charts = detail?.streams?.let(::streamCharts),
                zoneShares = detail?.metrics?.hrZoneSeconds?.let(::zoneShares).orEmpty(),
                units = settings.unitSystem
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityDetailUiState())

    fun retry() = retryable.retry()

    @AssistedFactory
    interface Factory {
        fun create(activityId: Long): ActivityDetailViewModel
    }
}

/** Downsamples streams for the HR and pace charts (speed is averaged before it becomes pace). */
internal fun streamCharts(streams: ActivityStreams): StreamCharts? {
    if (streams.size < 2) return null
    val stride = ChartMath.downsampleStride(streams.size, MAX_CHART_POINTS)
    val time = streams.timeSec.indices.step(stride).map { streams.timeSec[it] }
    val hr = streams.heartRate?.takeIf { arr -> arr.any { !it.isNaN() } }?.let { ChartMath.downsample(it, MAX_CHART_POINTS) }
    val pace = streams.speedMps?.takeIf { arr -> arr.any { !it.isNaN() } }
        ?.let { ChartMath.downsample(it, MAX_CHART_POINTS).map(::paceSecPerKm) }
    if (hr == null && pace == null) return null
    return StreamCharts(time, hr, pace)
}
