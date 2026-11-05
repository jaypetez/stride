package org.stride.feature.today

import androidx.compose.runtime.Composable
import org.stride.core.data.insights.VolumeDelta
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.Activity
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.FormBand
import org.stride.core.model.LoadMethod
import org.stride.core.model.SportType
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.science.model.WorkoutType

/** Hand-written sample state for IDE previews (screenshot tests use the real demo pipeline). */
internal object TodaySamples {
    val content = TodayContent(
        today = "2026-11-01",
        form = DailyLoadPoint("2026-11-01", load = 64.0, ctl = 48.2, atl = 52.6, tsb = -4.4, acwr = 1.08),
        band = FormBand.NEUTRAL,
        acwr = 1.08,
        acwrFlag = null,
        lastRun = ActivityWithMetrics(
            Activity(
                id = 1, name = "Long run", sport = SportType.RUN, startEpochSec = 1_793_516_400, timeZone = "UTC",
                durationSec = 5_400.0, movingTimeSec = 5_350.0, distanceM = 16_200.0, elevationGainM = 120.0, avgHr = 142.0,
                maxHr = 160.0, avgSpeedMps = 3.03, maxSpeedMps = 3.6, avgCadenceSpm = 172.0, calories = 1100.0, source = DataSource.DEMO
            ),
            ActivityMetrics(activityId = 1, load = 96.0, loadMethod = LoadMethod.RTSS)
        ),
        thisWeek = WeeklyVolume(distanceM = 42_100.0, durationSec = 14_000.0, load = 290.0, runs = 5),
        lastWeek = WeeklyVolume(distanceM = 38_600.0, durationSec = 13_100.0, load = 270.0, runs = 4),
        weekDelta = VolumeDelta(3_500.0, 900.0, 20.0, 1),
        wellness = DailyWellness(
            date = "2026-11-01", restingHr = 49, hrvLastNightMs = 61.0, hrvStatus = "BALANCED", sleepSec = 26_400,
            sleepScore = 81, stressAvg = 27, bodyBatteryHigh = 88, bodyBatteryLow = 21
        ),
        nextWorkout = WorkoutSuggestion(
            type = WorkoutType.EASY,
            title = "Easy aerobic run",
            description = "45 min conversational pace.",
            targetDurationSec = 2_700.0,
            targetPaceSecPerKm = 345.0,
            targetHrZone = 2,
            rationale = "Fatigue is slightly above fitness; keep today easy."
        )
    )
}

@ThemePreviews
@Composable
private fun TodayPreview() {
    StrideTheme {
        TodayScreen(state = TodayUiState(isLoading = false, content = TodaySamples.content), onRefresh = {}, onOpenActivity = {})
    }
}

@ThemePreviews
@Composable
private fun TodayEmptyPreview() {
    StrideTheme { TodayScreen(state = TodayUiState(isLoading = false), onRefresh = {}, onOpenActivity = {}) }
}
