package org.stride.core.data

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.sync.SyncFailure
import org.stride.core.data.sync.SyncOutcome
import org.stride.core.data.sync.SyncProgress
import org.stride.core.garmin.GarminError
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.SportType

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GarminSyncerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val today = LocalDate.parse("2026-11-01")

    /** One easy run every 3 days for a year. */
    private val history: List<Activity> = (0 until 365 step 3).map { back ->
        val date = today.minusDays(back.toLong())
        Activity(
            id = 1000L + back,
            name = "Run $back",
            sport = SportType.RUN,
            startEpochSec = date.atTime(7, 0).toEpochSecond(ZoneOffset.UTC),
            timeZone = "UTC",
            durationSec = 3000.0,
            movingTimeSec = 3000.0,
            distanceM = 9000.0,
            elevationGainM = 30.0,
            avgHr = 145.0,
            maxHr = 165.0,
            avgSpeedMps = 3.0,
            maxSpeedMps = 3.6,
            avgCadenceSpm = 170.0,
            calories = 500.0,
            source = DataSource.GARMIN
        )
    }
    private val wellness = (0 until 365).map { DailyWellness(date = today.minusDays(it.toLong()).toString(), restingHr = 50) }

    @Test
    fun backfillsConfiguredWindowNewestFirstInTwentyEightDayWindows() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val client = FakeGarminClient(history, wellness)
        val outcome = env.syncer(client).sync()
        assertIs<SyncOutcome.Success>(outcome)
        // 180-day default window: incremental week first, then newest-first windows.
        assertEquals(today, client.activityCalls.first().second)
        assertTrue(client.activityCalls.all { (s, e) -> java.time.temporal.ChronoUnit.DAYS.between(s, e) < 28 })
        val backfill = client.activityCalls.drop(1)
        assertTrue(backfill.zipWithNext().all { (newer, older) -> older.second < newer.first })
        val target = today.minusDays(179)
        assertEquals(target, client.activityCalls.minOf { it.first })
        val expected = history.count { !LocalDate.ofEpochDay(it.startEpochSec / 86_400).isBefore(target) }
        assertEquals(expected, env.db.activityDao().count())
        val state = env.db.syncStateDao().get()!!
        assertTrue(state.backfillComplete)
        assertIs<SyncProgress.Succeeded>(env.status.progress.value)
        // Metrics were computed for every activity and the PMC reaches today.
        assertEquals(expected, env.db.metricsDao().allActivityMetrics().size)
        assertEquals(today.toString(), env.db.metricsDao().allDailyLoads().last().date)
    }

    @Test
    fun interruptedBackfillResumesFromCursor() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val failing = FakeGarminClient(history, wellness).apply { failActivitiesOnCall = 4 }
        val first = env.syncer(failing).sync()
        assertEquals(SyncOutcome.Retry(SyncFailure.OFFLINE), first)
        val cursor = LocalDate.parse(env.db.syncStateDao().get()!!.backfillCursorDate)
        // Windows 2 and 3 completed before the failure on call 4.
        assertTrue(cursor < today.minusDays(6))

        val resumed = FakeGarminClient(history, wellness)
        assertIs<SyncOutcome.Success>(env.syncer(resumed).sync())
        val backfillCalls = resumed.activityCalls.drop(1)
        assertTrue(backfillCalls.all { it.second < cursor }, "resume must not refetch completed windows")
    }

    @Test
    fun rerunningTheSameWindowCreatesNoDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        env.syncer(FakeGarminClient(history, wellness)).sync()
        val activities = env.db.activityDao().count()
        val days = env.db.wellnessDao().all().size
        val metrics = env.db.metricsDao().allActivityMetrics()
        env.db.syncStateDao().upsert(env.db.syncStateDao().get()!!.copy(backfillComplete = false, backfillCursorDate = null))
        val second = env.syncer(FakeGarminClient(history, wellness)).sync()
        assertEquals(SyncOutcome.Success(0), second)
        assertEquals(activities, env.db.activityDao().count())
        assertEquals(days, env.db.wellnessDao().all().size)
        assertEquals(metrics, env.db.metricsDao().allActivityMetrics())
    }

    @Test
    fun rejectedKeyStopsSyncAndFlagsConnectScreen() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val client = FakeGarminClient(history).apply {
            failActivitiesOnCall = 1
            failWith = GarminError.KeyInvalid
        }
        assertEquals(SyncOutcome.Stop(SyncFailure.KEY_INVALID), env.syncer(client).sync())
        assertTrue(env.prefs.current().garminKeyRejected)
        assertEquals(1, client.activityCalls.size)
        assertEquals(SyncProgress.Failed(SyncFailure.KEY_INVALID), env.status.progress.value)
    }

    @Test
    fun runsWithoutStreamsRequestFitFilesAndSkipFailures() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val client = FakeGarminClient(history.take(3))
        assertIs<SyncOutcome.Success>(env.syncer(client).sync())
        assertEquals(history.take(3).map { it.id }.toSet(), client.fitRequests.toSet())
    }

    @Test
    fun demoModeNeverCallsGarmin() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        env.prefs.setDemoMode(true)
        val client = FakeGarminClient(history)
        assertEquals(SyncOutcome.Success(0), env.syncer(client).sync())
        assertTrue(client.activityCalls.isEmpty())
    }
}
