package org.stride.feature.fitness

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.database.entity.SyncStateEntity
import org.stride.core.database.toEntity
import org.stride.core.model.Activity
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DataSource
import org.stride.core.model.SportType
import org.stride.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FitnessViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun ReceiveTurbine<FitnessUiState>.awaitWhere(predicate: (FitnessUiState) -> Boolean): FitnessUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    @Test
    fun demoInsightsAreShownAndRangeChipsFilterThePmc() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val expected = fx.insights.fitness()
        val vm = FitnessViewModel(fx.insights, fx.prefs)
        vm.uiState.test {
            val state = awaitWhere { !it.isLoading }
            assertEquals(expected, state.insights)
            assertEquals(FitnessRange.THREE_MONTHS, state.range)
            assertEquals(91, state.window.size)
            assertEquals(expected.pmc.last(), state.window.last())

            vm.selectRange(FitnessRange.SIX_WEEKS)
            assertEquals(42, awaitWhere { it.range == FitnessRange.SIX_WEEKS }.window.size)
            vm.selectRange(FitnessRange.ONE_YEAR)
            val year = awaitWhere { it.range == FitnessRange.ONE_YEAR }
            assertEquals(expected.pmc.size.coerceAtMost(365), year.window.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** AUD-032: a read error is the error state, and "Try again" reloads. */
    @Test
    fun loadErrorShowsErrorStateAndRetryRecovers() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val (flaky, _) = fx.flakyPrefs()
        val vm = FitnessViewModel(fx.insights, flaky)
        vm.uiState.test {
            assertTrue(awaitWhere { it.loadFailed }.insights == null)
            vm.retry()
            assertNotNull(awaitWhere { !it.loadFailed && !it.isLoading }.insights)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun noDataIsEmpty() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        FitnessViewModel(fx.insights, fx.prefs).uiState.test {
            assertTrue(awaitWhere { !it.isLoading }.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** MVP-024: while the backfill runs, the PMC so far is shown with an "importing history" note. */
    @Test
    fun incompleteBackfillShowsThePmcWithAnImportingNote() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val vm = FitnessViewModel(fx.insights, fx.prefs)
        vm.uiState.test {
            val done = awaitWhere { !it.isLoading && !it.isEmpty }
            assertFalse(done.showImportingNote)
            fx.holder.db.syncStateDao().upsert(
                SyncStateEntity(
                    backfillTargetDate = "2026-05-06",
                    backfillCursorDate = "2026-09-01",
                    backfillComplete = false,
                    lastSuccessEpochSec = null
                )
            )
            val importing = awaitWhere { it.importingHistory }
            assertTrue(importing.showImportingNote)
            assertFalse(importing.isEmpty)
            assertFalse(importing.isCalculating)
            assertTrue(importing.window.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** MVP-024: runs stored but no daily loads yet (sync before its first recompute) is "calculating", not empty. */
    @Test
    fun runsWithoutDailyLoadsAreCalculatingNotEmpty() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        val run = Activity(
            id = 1,
            name = "Run",
            sport = SportType.RUN,
            startEpochSec = 1_793_000_000,
            timeZone = "UTC",
            durationSec = 3000.0,
            movingTimeSec = 3000.0,
            distanceM = 9000.0,
            elevationGainM = null,
            avgHr = 145.0,
            maxHr = null,
            avgSpeedMps = 3.0,
            maxSpeedMps = null,
            avgCadenceSpm = null,
            calories = null,
            source = DataSource.GARMIN
        )
        fx.holder.db.activityDao().upsert(listOf(run.toEntity()))
        FitnessViewModel(fx.insights, fx.prefs).uiState.test {
            val state = awaitWhere { !it.isLoading && it.hasActivities }
            assertTrue(state.isCalculating)
            assertFalse(state.isEmpty)
            assertFalse(state.showImportingNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun windowEndsAtTheNewestPoint() {
        val start = LocalDate.parse("2026-01-01")
        val pmc = (0 until 100).map { DailyLoadPoint(start.plusDays(it.toLong()).toString(), 0.0, 0.0, 0.0, 0.0) }
        val window = pmcWindow(pmc, FitnessRange.SIX_WEEKS)
        assertEquals(42, window.size)
        assertEquals(pmc.last(), window.last())
        assertEquals(pmc, pmcWindow(pmc, FitnessRange.ONE_YEAR))
        assertTrue(pmcWindow(emptyList(), FitnessRange.ONE_YEAR).isEmpty())
        assertNotNull(window.first())
    }
}
