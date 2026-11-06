package org.stride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.stride.core.database.entity.ActivityEntity
import org.stride.core.database.entity.ActivityMetricsEntity
import org.stride.core.database.entity.DailyLoadEntity
import org.stride.core.database.entity.DailyWellnessEntity
import org.stride.core.database.entity.LapEntity
import org.stride.core.database.entity.StreamEntity
import org.stride.core.database.entity.SyncStateEntity

@Dao
interface ActivityDao {
    @Upsert
    suspend fun upsert(activities: List<ActivityEntity>)

    @Query("SELECT * FROM activity ORDER BY start_epoch_sec DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity ORDER BY start_epoch_sec DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ActivityEntity>

    @Query("SELECT * FROM activity WHERE id = :id")
    fun observe(id: Long): Flow<ActivityEntity?>

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Query("SELECT * FROM activity WHERE local_date BETWEEN :from AND :to ORDER BY start_epoch_sec DESC")
    fun observeBetween(from: String, to: String): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity ORDER BY start_epoch_sec ASC")
    suspend fun allChronological(): List<ActivityEntity>

    /** Activities whose local date is in [from]..[to] (ISO dates), oldest first. */
    @Query("SELECT * FROM activity WHERE local_date BETWEEN :from AND :to ORDER BY start_epoch_sec ASC")
    suspend fun between(from: String, to: String): List<ActivityEntity>

    /** Activities that started at or after [fromEpochSec], oldest first. */
    @Query("SELECT * FROM activity WHERE start_epoch_sec >= :fromEpochSec ORDER BY start_epoch_sec ASC")
    suspend fun startedSince(fromEpochSec: Long): List<ActivityEntity>

    @Query("SELECT id FROM activity WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("SELECT id FROM activity WHERE has_streams = 0 AND sport IN (:sports) ORDER BY start_epoch_sec DESC")
    suspend fun idsMissingStreams(sports: List<String>): List<Long>

    @Query("UPDATE activity SET has_streams = 1 WHERE id = :id")
    suspend fun markHasStreams(id: Long)

    @Query("SELECT COUNT(*) FROM activity")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM activity)")
    fun observeHasAny(): Flow<Boolean>
}

@Dao
interface LapDao {
    @Upsert
    suspend fun upsert(laps: List<LapEntity>)

    @Query("DELETE FROM lap WHERE activity_id = :activityId")
    suspend fun deleteFor(activityId: Long)

    @Transaction
    suspend fun replace(activityId: Long, laps: List<LapEntity>) {
        deleteFor(activityId)
        upsert(laps)
    }

    @Query("SELECT * FROM lap WHERE activity_id = :activityId ORDER BY lap_index")
    fun observe(activityId: Long): Flow<List<LapEntity>>

    @Query("SELECT * FROM lap WHERE activity_id = :activityId ORDER BY lap_index")
    suspend fun get(activityId: Long): List<LapEntity>
}

@Dao
interface StreamDao {
    @Upsert
    suspend fun upsert(stream: StreamEntity)

    @Query("SELECT * FROM stream WHERE activity_id = :activityId")
    suspend fun get(activityId: Long): StreamEntity?

    @Query("SELECT * FROM stream WHERE activity_id = :activityId")
    fun observe(activityId: Long): Flow<StreamEntity?>
}

@Dao
interface WellnessDao {
    @Upsert
    suspend fun upsert(days: List<DailyWellnessEntity>)

    @Query("SELECT * FROM daily_wellness WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeBetween(from: String, to: String): Flow<List<DailyWellnessEntity>>

    @Query("SELECT * FROM daily_wellness ORDER BY date DESC LIMIT 1")
    fun observeLatest(): Flow<DailyWellnessEntity?>

    @Query("SELECT * FROM daily_wellness ORDER BY date")
    suspend fun all(): List<DailyWellnessEntity>
}

@Dao
interface MetricsDao {
    @Upsert
    suspend fun upsertActivityMetrics(metrics: List<ActivityMetricsEntity>)

    @Query("SELECT * FROM activity_metrics WHERE activity_id = :activityId")
    fun observeActivityMetrics(activityId: Long): Flow<ActivityMetricsEntity?>

    @Query("SELECT * FROM activity_metrics")
    fun observeAllActivityMetrics(): Flow<List<ActivityMetricsEntity>>

    @Query("SELECT * FROM activity_metrics")
    suspend fun allActivityMetrics(): List<ActivityMetricsEntity>

    @Query("SELECT * FROM activity_metrics WHERE activity_id IN (:activityIds)")
    suspend fun activityMetricsFor(activityIds: List<Long>): List<ActivityMetricsEntity>

    @Upsert
    suspend fun upsertDailyLoads(days: List<DailyLoadEntity>)

    @Query("DELETE FROM daily_load WHERE date >= :from")
    suspend fun deleteDailyLoadsFrom(from: String)

    @Transaction
    suspend fun replaceDailyLoadsFrom(from: String, days: List<DailyLoadEntity>) {
        deleteDailyLoadsFrom(from)
        upsertDailyLoads(days)
    }

    @Query("SELECT * FROM daily_load WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeDailyLoads(from: String, to: String): Flow<List<DailyLoadEntity>>

    @Query("SELECT * FROM daily_load ORDER BY date")
    suspend fun allDailyLoads(): List<DailyLoadEntity>

    @Query("SELECT * FROM daily_load ORDER BY date DESC LIMIT 1")
    fun observeLatestDailyLoad(): Flow<DailyLoadEntity?>
}

@Dao
interface SyncStateDao {
    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>
}
