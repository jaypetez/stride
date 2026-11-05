package org.stride.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.sync.GarminSyncer
import org.stride.core.data.sync.SyncLock
import org.stride.core.data.sync.SyncStatus
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.datastore.UserPreferences
import org.stride.core.garmin.GarminClient
import org.stride.core.testing.FakeStrideClock

/** In-memory Room + temp-file DataStore wiring shared by core:data tests. */
class TestEnv(scope: TestScope, private val dispatcher: CoroutineDispatcher, dir: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val holder = DatabaseHolder(
        factory = { Room.inMemoryDatabaseBuilder(context, StrideDatabase::class.java).allowMainThreadQueries().build() }
    )
    val prefs = UserPreferences(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(dir, "prefs.preferences_pb") })
    val clock = FakeStrideClock()
    val status = SyncStatus()
    val recomputer = ScienceMetricsRecomputer(holder, prefs, clock, dispatcher)
    val lock = SyncLock()

    fun syncer(client: GarminClient) = GarminSyncer(client, holder, prefs, clock, status, recomputer, lock, dispatcher)

    val db: StrideDatabase get() = holder.db
}
