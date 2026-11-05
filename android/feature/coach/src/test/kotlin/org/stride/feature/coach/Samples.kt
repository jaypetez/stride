package org.stride.feature.coach

import org.stride.core.data.insights.TodayInsights
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.model.Activity
import org.stride.core.model.ActivityDetail
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.ActivityStreams
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DataSource
import org.stride.core.model.FormBand
import org.stride.core.model.Lap
import org.stride.core.model.LoadMethod
import org.stride.core.model.SportType
import org.stride.core.science.model.AcwrFlag

object Samples {
    val activity = Activity(
        id = 42,
        name = "Morning Run",
        sport = SportType.RUN,
        startEpochSec = 1_793_520_000, // 2026-11-01T08:00Z
        timeZone = "Europe/London",
        durationSec = 3010.0,
        movingTimeSec = 3000.0,
        distanceM = 10_012.0,
        elevationGainM = 84.0,
        avgHr = 151.4,
        maxHr = 176.0,
        avgSpeedMps = 3.337,
        maxSpeedMps = 4.4,
        avgCadenceSpm = 172.0,
        calories = 690.0,
        source = DataSource.GARMIN
    )

    val metrics = ActivityMetrics(
        activityId = 42,
        load = 68.43,
        loadMethod = LoadMethod.RTSS,
        ngpMps = 3.36,
        intensityFactor = 0.8312,
        trimp = 101.2,
        hrZoneSeconds = listOf(120.0, 1500.0, 1080.0, 300.0, 0.0),
        efficiencyFactor = 1.3219,
        decouplingPct = 3.84,
        vdot = 48.27
    )

    val detail = ActivityDetail(
        activity = activity,
        metrics = metrics,
        laps = List(10) { Lap(42, it, it * 300.0, 300.0, 1000.0, 150.0, 160.0, 3.33, 8.0, 172.0) },
        streams = ActivityStreams(
            activityId = 42,
            timeSec = DoubleArray(3000) { it.toDouble() },
            heartRate = DoubleArray(3000) { 140.0 + it % 20 },
            speedMps = DoubleArray(3000) { 3.3 }
        )
    )

    val today = TodayInsights(
        today = "2026-11-01",
        form = DailyLoadPoint(date = "2026-11-01", load = 68.4, ctl = 52.37, atl = 61.04, tsb = -8.67, acwr = 1.16),
        band = FormBand.NEUTRAL,
        acwr = 1.1634,
        acwrFlag = AcwrFlag.OK,
        thisWeek = WeeklyVolume(distanceM = 32_400.0, durationSec = 10_800.0, load = 210.5, runs = 4),
        lastWeek = WeeklyVolume(distanceM = 28_100.0, durationSec = 9_600.0, load = 180.0, runs = 3),
        nextWorkout = null
    )
}
