package org.stride.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import org.stride.core.database.dao.ActivityDao
import org.stride.core.database.dao.LapDao
import org.stride.core.database.dao.MetricsDao
import org.stride.core.database.dao.StreamDao
import org.stride.core.database.dao.SyncStateDao
import org.stride.core.database.dao.WellnessDao
import org.stride.core.database.entity.ActivityEntity
import org.stride.core.database.entity.ActivityMetricsEntity
import org.stride.core.database.entity.DailyLoadEntity
import org.stride.core.database.entity.DailyWellnessEntity
import org.stride.core.database.entity.LapEntity
import org.stride.core.database.entity.StreamEntity
import org.stride.core.database.entity.SyncStateEntity

@Database(
    entities = [
        ActivityEntity::class,
        LapEntity::class,
        StreamEntity::class,
        DailyWellnessEntity::class,
        ActivityMetricsEntity::class,
        DailyLoadEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class StrideDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    abstract fun lapDao(): LapDao

    abstract fun streamDao(): StreamDao

    abstract fun wellnessDao(): WellnessDao

    abstract fun metricsDao(): MetricsDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "stride.db"

        /** Demo mode runs on its own file, deleted when demo mode is exited (MVP-020). */
        const val DEMO_NAME = "stride-demo.db"
    }
}
