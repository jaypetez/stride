package org.stride.core.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideDispatchers
import org.stride.core.data.plan.PlanRepository
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences

/** Deletes local data (Connect → Disconnect, Settings → Delete all data, MVP-005/021). */
interface DataEraser {
    /** Deletes every synced Garmin record (the real database only; demo data is untouched). */
    suspend fun clearSyncedData()

    /**
     * Deletes everything Stride stores: both databases, preferences (so onboarding
     * runs again) and every secret, and cancels sync.
     */
    suspend fun wipeEverything()
}

@Singleton
class RoomDataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val secrets: SecretStore,
    private val sync: SyncScheduler,
    private val plans: PlanRepository,
    @Dispatcher(StrideDispatchers.IO) private val io: CoroutineDispatcher
) : DataEraser {
    override suspend fun clearSyncedData() = withContext(io) {
        if (holder.isDemo) {
            // The real database is closed while demo mode is active: delete its file.
            context.deleteDatabase(StrideDatabase.NAME)
        } else {
            holder.db.clearAllTables()
        }
        Unit
    }

    override suspend fun wipeEverything() = withContext(io) {
        sync.cancelAll()
        holder.switchTo(false)
        holder.db.clearAllTables()
        context.deleteDatabase(StrideDatabase.DEMO_NAME)
        secrets.wipe()
        plans.delete()
        prefs.wipe()
    }
}
