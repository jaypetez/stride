package org.stride.feature.today

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.stride.core.data.insights.deltaFrom
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun DemoDataFixture.viewModel() = TodayViewModel(insights, activities, wellness, prefs, sync)

    private suspend fun ReceiveTurbine<TodayUiState>.awaitLoaded(): TodayUiState {
        var state = awaitItem()
        while (state.isLoading) state = awaitItem()
        return state
    }

    @Test
    fun demoDataShowsFormLastRunWeekAndNextWorkout() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val expected = fx.insights.today()
        val newestRun = fx.activities.observeActivities().first().filter { it.activity.sport.isRun }.maxBy { it.activity.startEpochSec }

        fx.viewModel().uiState.test {
            val state = awaitLoaded()
            val content = assertNotNull(state.content)
            assertTrue(state.demoMode)
            assertEquals(expected.form, content.form)
            assertEquals(expected.band, content.band)
            assertEquals(newestRun.activity.id, content.lastRun?.activity?.id)
            assertEquals(expected.thisWeek.deltaFrom(expected.lastWeek), content.weekDelta)
            assertEquals(expected.nextWorkout, assertNotNull(content.nextWorkout))
            assertNotNull(content.wellness?.restingHr)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun noDataIsTheEmptyState() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.viewModel().uiState.test {
            val state = awaitLoaded()
            assertNull(state.content)
            assertFalse(state.demoMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pullToRefreshRequestsASyncAndShowsProgress() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val vm = fx.viewModel()
        vm.uiState.test {
            assertFalse(awaitLoaded().isRefreshing)
            vm.refresh()
            assertEquals(1, fx.sync.syncRequests)
            fx.sync.state.value = SyncProgress.Running("activities", 3, 10)
            assertTrue(awaitItem().isRefreshing)
            fx.sync.state.value = SyncProgress.Succeeded(atEpochSec = 1L, newActivities = 2)
            assertFalse(awaitItem().isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
