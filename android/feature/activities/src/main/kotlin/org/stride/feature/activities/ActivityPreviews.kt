package org.stride.feature.activities

import androidx.compose.runtime.Composable
import kotlin.math.sin
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.Activity
import org.stride.core.model.ActivityDetail
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.ActivityStreams
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DataSource
import org.stride.core.model.Lap
import org.stride.core.model.LoadMethod
import org.stride.core.model.SportType

/** Hand-written sample data for IDE previews (screenshot tests use the real demo pipeline). */
internal object ActivitySamples {
    private val run = Activity(
        id = 7, name = "Tempo run", sport = SportType.RUN, startEpochSec = 1_793_430_000, timeZone = "UTC",
        durationSec = 3_000.0, movingTimeSec = 2_980.0, distanceM = 10_050.0, elevationGainM = 64.0, avgHr = 158.0,
        maxHr = 176.0, avgSpeedMps = 3.37, maxSpeedMps = 4.1, avgCadenceSpm = 176.0, calories = 700.0, source = DataSource.GARMIN,
        hasStreams = true
    )
    private val metrics = ActivityMetrics(
        activityId = 7,
        load = 72.0,
        loadMethod = LoadMethod.RTSS,
        intensityFactor = 0.93,
        hrZoneSeconds = listOf(120.0, 600.0, 1_200.0, 900.0, 160.0),
        efficiencyFactor = 1.28,
        decouplingPct = 3.4
    )
    val list = ActivitiesUiState(isLoading = false, months = groupByMonth(listOf(ActivityWithMetrics(run, metrics))))
    val detail: ActivityDetailUiState = run {
        val n = 600
        val streams = ActivityStreams(
            activityId = 7,
            timeSec = DoubleArray(n) { it * 5.0 },
            heartRate = DoubleArray(n) { 140 + 25 * (it / n.toDouble()) + 3 * sin(it / 20.0) },
            speedMps = DoubleArray(n) { 3.3 + 0.2 * sin(it / 30.0) }
        )
        val laps = (0 until 10).map { Lap(7, it, it * 298.0, 298.0, 1_000.0, 150.0 + it, 165.0, 3.36, 6.0, 176.0) }
        ActivityDetailUiState(
            isLoading = false,
            detail = ActivityDetail(run, metrics, laps, streams),
            charts = streamCharts(streams),
            zoneShares = listOf(0.04, 0.2, 0.4, 0.3, 0.06)
        )
    }
}

@ThemePreviews
@Composable
private fun ActivitiesPreview() {
    StrideTheme { ActivitiesScreen(state = ActivitySamples.list, onOpenActivity = {}) }
}

@ThemePreviews
@Composable
private fun ActivityDetailPreview() {
    StrideTheme { ActivityDetailScreen(state = ActivitySamples.detail, onBack = {}, onAskCoach = {}) }
}
