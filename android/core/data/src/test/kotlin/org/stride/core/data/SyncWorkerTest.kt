package org.stride.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlin.test.assertEquals
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
import org.stride.core.data.sync.SyncWorker
import org.stride.core.garmin.GarminError

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun outcomesMapToWorkManagerResults() {
        assertEquals(ListenableWorker.Result.success(), SyncWorker.outcomeToResult(SyncOutcome.Success(3), 0))
        assertEquals(ListenableWorker.Result.retry(), SyncWorker.outcomeToResult(SyncOutcome.Retry(SyncFailure.OFFLINE), 0))
        assertEquals(ListenableWorker.Result.failure(), SyncWorker.outcomeToResult(SyncOutcome.Retry(SyncFailure.OFFLINE), 4))
        assertEquals(ListenableWorker.Result.failure(), SyncWorker.outcomeToResult(SyncOutcome.Stop(SyncFailure.KEY_INVALID), 0))
    }

    @Test
    fun workerRunsSyncerWithFakeClient() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val keyInvalid = FakeGarminClient(emptyList()).apply {
            failActivitiesOnCall = 1
            failWith = GarminError.KeyInvalid
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        SyncWorker(appContext, workerParameters, env.syncer(keyInvalid))
                }
            )
            .build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }
}
