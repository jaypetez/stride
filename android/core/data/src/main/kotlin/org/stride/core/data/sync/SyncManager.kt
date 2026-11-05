package org.stride.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** What screens need from sync: progress plus "sync now" (pull-to-refresh). Lets ViewModels be tested without WorkManager. */
interface SyncController {
    val progress: StateFlow<SyncProgress>

    /** Pull-to-refresh / after connecting: run now. */
    fun syncNow()
}

/** Scheduling on top of [SyncController]; [SyncManager] is the WorkManager-backed implementation (fakes in tests). */
interface SyncScheduler : SyncController {
    /** Idempotent: keeps an existing schedule. */
    fun schedulePeriodic()

    /** Cancels the periodic and one-off jobs (disconnect / delete all data). */
    fun cancelAll()
}

/** Schedules Garmin sync: a daily periodic job plus expedited one-offs for pull-to-refresh. */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val status: SyncStatus
) : SyncScheduler {
    override val progress: StateFlow<SyncProgress> = status.progress

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEC, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Expedited when quota allows. */
    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEC, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        status.update(SyncProgress.Idle)
    }

    companion object {
        const val PERIODIC = "garmin-sync-daily"
        const val NOW = "garmin-sync-now"
        private const val BACKOFF_SEC = 60L
    }
}
