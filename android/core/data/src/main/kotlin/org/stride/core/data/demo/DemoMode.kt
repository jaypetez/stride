package org.stride.core.data.demo

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.database.toEntity
import org.stride.core.datastore.UserPreferences

/** Enter / exit demo mode; [DemoMode] implements it (fakes in screen tests). */
interface DemoController {
    suspend fun enter()

    suspend fun exit()
}

/**
 * "Try demo" (MVP-020): loads the synthetic dataset into a separate demo
 * database and runs the normal recompute pipeline over it, so every number on
 * screen is computed exactly as for real data. Exiting deletes the demo file.
 */
@Singleton
class DemoMode @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    private val recomputer: MetricsRecomputer,
    @Dispatcher(StrideDispatchers.IO) private val io: CoroutineDispatcher
) : DemoController {
    /** Re-attaches the demo database at startup if the app was left in demo mode. */
    suspend fun restore() = withContext(io) {
        if (prefs.current().demoMode) holder.switchTo(true)
    }

    override suspend fun enter() = withContext(io) {
        holder.switchTo(false)
        holder.awaitRetired() // never delete a file under a connection that is still closing
        context.deleteDatabase(StrideDatabase.DEMO_NAME)
        holder.switchTo(true)
        load(holder.db)
        prefs.setDemoMode(true)
    }

    override suspend fun exit() = withContext(io) {
        holder.switchTo(false)
        prefs.setDemoMode(false)
        // The demo database closes after a grace period so in-flight queries finish (AUD-030); then delete it.
        holder.awaitRetired()
        context.deleteDatabase(StrideDatabase.DEMO_NAME)
        Unit
    }

    /** Inserts the dataset and computes metrics; public for tests on an in-memory database. */
    suspend fun load(db: StrideDatabase) {
        val data = DemoDataGenerator.generate(clock.today())
        db.withTransaction {
            db.activityDao().upsert(data.activities.map { it.activity.toEntity(clock.zone()) })
            data.activities.forEach { demo ->
                db.lapDao().replace(demo.activity.id, demo.laps.map { it.toEntity() })
                demo.streams?.let { db.streamDao().upsert(it.toEntity()) }
            }
            db.wellnessDao().upsert(data.wellness.map { it.toEntity() })
        }
        recomputer.recompute(changedActivityIds = null, fromDate = null)
    }
}
