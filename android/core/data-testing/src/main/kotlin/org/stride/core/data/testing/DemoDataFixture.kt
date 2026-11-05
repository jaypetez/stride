package org.stride.core.data.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.plan.PlanRepository
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.data.repository.FitnessRepository
import org.stride.core.data.repository.WellnessRepository
import org.stride.core.data.sync.SyncController
import org.stride.core.data.sync.SyncProgress
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.datastore.UserPreferences
import org.stride.core.testing.FakeStrideClock

/**
 * The real repositories over an in-memory Room database and temp-file DataStores,
 * pinned to [FakeStrideClock]. Call [loadDemo] to run the demo dataset through
 * the recompute pipeline, so screens are tested on exactly what the app shows
 * in demo mode. Needs Robolectric (Room + Context).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoDataFixture(scope: TestScope, dir: File) {
    val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    val context: Context = ApplicationProvider.getApplicationContext()
    val holder = DatabaseHolder(
        factory = { Room.inMemoryDatabaseBuilder(context, StrideDatabase::class.java).allowMainThreadQueries().build() }
    )
    val prefsStore = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(dir, "prefs.preferences_pb") }
    val prefs = UserPreferences(prefsStore)
    val clock = FakeStrideClock()
    val recomputer = ScienceMetricsRecomputer(holder, prefs, clock, dispatcher)
    val activities = ActivityRepository(holder)
    val wellness = WellnessRepository(holder)
    val fitness = FitnessRepository(holder)
    val insights = InsightsRepository(holder, prefs, clock, recomputer, dispatcher)
    val plans = PlanRepository(
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(dir, "${PlanRepository.FILE_NAME}.preferences_pb") },
        holder,
        prefs,
        clock,
        recomputer,
        dispatcher
    )
    val sync = FakeSyncController()

    /** Preferences over the same file whose first [failures] reads throw (AUD-032 error states). */
    fun flakyPrefs(failures: Int = 1): Pair<UserPreferences, FlakyDataStore> = flakyPreferences(prefsStore, failures)

    suspend fun loadDemo() {
        DemoMode(context, holder, prefs, clock, recomputer, dispatcher).load(holder.db)
        prefs.setDemoMode(true)
    }
}

/** Records sync requests; tests drive [progress] directly. */
class FakeSyncController : SyncController {
    val state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    override val progress: StateFlow<SyncProgress> = state
    var syncRequests = 0
        private set

    override fun syncNow() {
        syncRequests++
    }
}
