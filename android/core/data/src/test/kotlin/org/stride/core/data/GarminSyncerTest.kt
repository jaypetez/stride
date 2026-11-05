package org.stride.core.data

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    /** AUD-026: an unexpected exception (not a typed Garmin error) still settles the status. */
    @Test
    fun unexpectedExceptionEndsInFailedWithReason() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val client = FakeGarminClient(history).apply { throwOnActivities = java.util.zip.ZipException("bad zip") }
        assertEquals(SyncOutcome.Retry(SyncFailure.UNEXPECTED), env.syncer(client).sync())
        assertEquals(SyncProgress.Failed(SyncFailure.UNEXPECTED), env.status.progress.value)
        // The next sync is not blocked by the failed one.
        assertIs<SyncOutcome.Success>(env.syncer(FakeGarminClient(history, wellness)).sync())
    }

    /** AUD-026: the daily job and "sync now" never interleave. */
    @Test
    fun concurrentSyncsRunSequentially() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val gate = CompletableDeferred<Unit>()
        val client = FakeGarminClient(history.take(5), wellness).apply { this.gate = gate }
        val first = async { env.syncer(client).sync() }
        client.entered.await()
        val second = async { env.syncer(client).sync() }
        // The first sync is parked inside Garmin; the second waits on the lock without calling Garmin.
        assertEquals(1, client.activityCalls.size)
        gate.complete(Unit)
        assertIs<SyncOutcome.Success>(first.await())
        assertIs<SyncOutcome.Success>(second.await())
        assertEquals(1, client.maxInFlight)
        assertTrue(client.activityCalls.size > 1)
    }

    /** AUD-026: a cancelled sync (worker stopped) leaves Idle, not a spinner stuck on Running. */
    @Test
    fun cancelledSyncSettlesToIdle() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val client = FakeGarminClient(history, wellness).apply { gate = CompletableDeferred() }
        val job = launch { env.syncer(client).sync() }
        client.entered.await()
        assertIs<SyncProgress.Running>(env.status.progress.value)
        job.cancel()
        job.join()
        assertEquals(SyncProgress.Idle, env.status.progress.value)
        // The lock was released: a fresh sync runs.
        assertIs<SyncOutcome.Success>(env.syncer(FakeGarminClient(history, wellness)).sync())
    }

    /** AUD-029: a run recorded in another time zone keeps its local date, whatever the phone's zone. */
    @Test
    fun runAbroadKeepsItsLocalDate() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        // 07:00 on 31 Oct in Tokyo is 22:00 on 30 Oct UTC; the phone (clock) is in UTC.
        val tokyo = history.first().copy(
            id = 42,
            startEpochSec = java.time.Instant.parse("2026-10-30T22:00:00Z").epochSecond,
            timeZone = "+09:00"
        )
        env.syncer(FakeGarminClient(listOf(tokyo), wellness)).sync()
        assertEquals("2026-10-31", env.db.activityDao().get(42)!!.localDate)
    }
}
