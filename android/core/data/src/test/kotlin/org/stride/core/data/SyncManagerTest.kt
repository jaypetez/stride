package org.stride.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.sync.SyncManager
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncStatus
import org.stride.core.data.sync.SyncWorker

/** Unique-work names, policies and constraints of the Garmin sync schedule (AUD-038). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val status = SyncStatus()
    private val manager = SyncManager(context, status)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun spec(info: WorkInfo) = assertNotNull(
        WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())
    )

    @Test
    fun periodicSyncIsDailyNeedsNetworkAndKeepsAnExistingSchedule() {
        manager.schedulePeriodic()
        val first = infos(SyncManager.PERIODIC).single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(TimeUnit.DAYS.toMillis(1), assertNotNull(first.periodicityInfo).repeatIntervalMillis)
        val spec = spec(first)
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(60), spec.backoffDelayDuration)

        // ExistingPeriodicWorkPolicy.KEEP: a second call does not replace the schedule.
        manager.schedulePeriodic()
        assertEquals(first.id, infos(SyncManager.PERIODIC).single().id)
    }

    @Test
    fun syncNowIsExpeditedNeedsNetworkAndDoesNotStackRequests() {
        manager.syncNow()
        val first = infos(SyncManager.NOW).single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state, "waits for the network constraint")
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        val spec = spec(first)
        assertTrue(spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)

        // ExistingWorkPolicy.KEEP: pull-to-refresh while one is pending does not queue another.
        manager.syncNow()
        assertEquals(first.id, infos(SyncManager.NOW).single().id)
        assertTrue(infos(SyncManager.PERIODIC).isEmpty(), "sync now does not create the daily schedule")
    }

    @Test
    fun cancelAllCancelsBothJobsAndResetsProgress() {
        manager.schedulePeriodic()
        manager.syncNow()
        status.update(SyncProgress.Running("activities", 1, 2))
        manager.cancelAll()
        assertEquals(WorkInfo.State.CANCELLED, infos(SyncManager.PERIODIC).single().state)
        assertEquals(WorkInfo.State.CANCELLED, infos(SyncManager.NOW).single().state)
        assertEquals(SyncProgress.Idle, manager.progress.value)
    }

    @Test
    fun progressIsTheSharedSyncStatus() {
        status.update(SyncProgress.Succeeded(1, 2))
        assertEquals(SyncProgress.Succeeded(1, 2), manager.progress.value)
    }
}
