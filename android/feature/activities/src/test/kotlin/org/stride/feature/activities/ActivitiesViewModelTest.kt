package org.stride.feature.activities

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.designsystem.chart.ChartMath
import org.stride.core.model.ActivityStreams
import org.stride.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivitiesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun <T> ReceiveTurbine<T>.awaitWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    @Test
    fun listIsNewestFirstAndGroupedByMonth() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val total = fx.activities.observeActivities().first().size
        ActivitiesViewModel(fx.activities, fx.prefs).uiState.test {
            val state = awaitWhere { !it.isLoading }
            val flat = state.months.flatMap { it.activities }
            assertEquals(total, flat.size)
            assertEquals(flat.sortedByDescending { it.activity.startEpochSec }, flat)
            assertEquals(state.months.map { it.key }.sortedDescending(), state.months.map { it.key })
            assertTrue(state.months.first().title in setOf("November 2026", "October 2026"))
            assertTrue(flat.all { it.metrics != null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun detailCarriesMetricsLapsChartsAndZoneShares() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val run = fx.activities.observeActivities().first().first { it.activity.hasStreams && it.activity.sport.isRun }
        ActivityDetailViewModel(run.activity.id, fx.activities, fx.prefs).uiState.test {
            val state = awaitWhere { !it.isLoading }
            val detail = assertNotNull(state.detail)
            assertEquals(run.activity.id, detail.activity.id)
            assertTrue(detail.laps.isNotEmpty())
            val charts = assertNotNull(state.charts)
            assertTrue(charts.timeSec.size <= MAX_CHART_POINTS)
            assertEquals(charts.timeSec.size, assertNotNull(charts.heartRate).size)
            assertEquals(charts.timeSec.size, assertNotNull(charts.paceSecPerKm).size)
            assertEquals(5, state.zoneShares.size)
            assertEquals(1.0, state.zoneShares.sum(), 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unknownActivityIsNotFound() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        ActivityDetailViewModel(424_242L, fx.activities, fx.prefs).uiState.test {
            assertNull(awaitWhere { !it.isLoading }.detail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun streamChartsDownsampleAndConvertSpeedToPace() {
        val n = 3_000
        val streams = ActivityStreams(
            activityId = 1,
            timeSec = DoubleArray(n) { it.toDouble() },
            heartRate = DoubleArray(n) { if (it in 100..199) Double.NaN else 150.0 },
            speedMps = DoubleArray(n) { 4.0 }
        )
        val charts = assertNotNull(streamCharts(streams))
        val stride = ChartMath.downsampleStride(n, MAX_CHART_POINTS)
        assertEquals(0.0, charts.timeSec[0])
        assertEquals(stride.toDouble(), charts.timeSec[1])
        assertTrue(charts.paceSecPerKm!!.all { it == 250.0 })
        assertTrue(charts.heartRate!!.any { it.isNaN() }, "an all-gap bucket stays a gap")
        assertNull(streamCharts(ActivityStreams(1, DoubleArray(10) { it.toDouble() })))
    }
}
