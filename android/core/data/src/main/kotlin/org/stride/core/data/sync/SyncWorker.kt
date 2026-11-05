package org.stride.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs [GarminSyncer]; maps its outcome onto WorkManager's retry semantics (MVP-008). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: GarminSyncer
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = outcomeToResult(syncer.sync(), runAttemptCount)

    companion object {
        const val MAX_ATTEMPTS = 5

        fun outcomeToResult(outcome: SyncOutcome, attempt: Int): Result = when (outcome) {
            is SyncOutcome.Success -> Result.success()

            is SyncOutcome.Retry -> if (attempt + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

            // A missing / rejected key needs the user; retrying would only hammer Garmin.
            is SyncOutcome.Stop -> Result.failure()
        }
    }
}
