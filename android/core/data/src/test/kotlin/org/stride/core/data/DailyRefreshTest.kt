package org.stride.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.insights.DATE_POLL_MS
import org.stride.core.data.insights.dates
import org.stride.core.database.toEntity
import org.stride.core.model.DailyWellness
import org.stride.core.testing.FakeStrideClock

/** AUD-031: the date ticker and demo data that follows the calendar. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyRefreshTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun tickerEmitsTheNewDateJustAfterLocalMidnight() = runTest {
        val clock = FakeStrideClock(Instant.parse("2026-11-01T22:59:00Z"), ZoneId.of("Europe/Berlin")) // 23:59 local
        val seen = mutableListOf<LocalDate>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { clock.dates().collect { seen += it } }
        assertEquals(listOf(LocalDate.parse("2026-11-01")), seen)

        testScheduler.advanceTimeBy(30_000)
        assertEquals(1, seen.size, "nothing before midnight")
        clock.instant = Instant.parse("2026-11-01T23:00:01Z") // 00:00:01 local
        testScheduler.advanceTimeBy(31_000)
        assertEquals(listOf(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")), seen)
        job.cancel()
    }

    @Test
    fun tickerRechecksAtLeastEveryPollInterval() = runTest {
        val clock = FakeStrideClock(Instant.parse("2026-11-01T07:00:00Z"))
        val seen = mutableListOf<LocalDate>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { clock.dates().collect { seen += it } }
        // The clock jumps (manual change or time-zone travel) long before the computed midnight.
        clock.instant = Instant.parse("2026-11-03T07:00:00Z")
        testScheduler.advanceTimeBy(DATE_POLL_MS + 1)
        assertEquals(LocalDate.parse("2026-11-03"), seen.last())
        job.cancel()
    }

    @Test
    fun restoringStaleDemoRegeneratesItAnchoredToToday() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val demo = DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher)
        env.prefs.setDemoMode(true)
        env.holder.switchTo(true)
        demo.load(env.db)
        env.db.wellnessDao().upsert(listOf(DailyWellness(date = "1999-01-01").toEntity()))

        // Same day: kept as is.
        demo.restore()
        assertTrue(env.db.wellnessDao().all().any { it.date == "1999-01-01" })

        // Three days later the demo is regenerated for the new today.
        env.clock.instant = Instant.parse("2026-11-04T07:00:00Z")
        demo.restore()
        assertTrue(env.db.wellnessDao().all().none { it.date == "1999-01-01" }, "old data is replaced")
        assertEquals("2026-11-04", env.db.metricsDao().allDailyLoads().last().date)
        val newestRun = env.db.activityDao().allChronological().maxOf { it.localDate }
        assertTrue(newestRun >= "2026-10-29", "activities follow the new anchor: newest $newestRun")
    }
}
