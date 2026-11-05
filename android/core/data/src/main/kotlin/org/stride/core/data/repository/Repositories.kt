package org.stride.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.entity.ActivityMetricsEntity
import org.stride.core.database.entity.DailyLoadEntity
import org.stride.core.database.toModel
import org.stride.core.model.ActivityDetail
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.ActivityWithMetrics
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DailyWellness
import org.stride.core.model.LoadMethod

/*
 * Offline-first repositories (ANDF-004): every read is a Flow from Room, and
 * every Flow follows the active database (real or demo) via DatabaseHolder.
 * No UI reads from the network.
 */

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActivityRepository @Inject constructor(private val holder: DatabaseHolder) {
    fun observeActivities(): Flow<List<ActivityWithMetrics>> = holder.current.flatMapLatest { db ->
        combine(db.activityDao().observeAll(), db.metricsDao().observeAllActivityMetrics()) { activities, metrics ->
            val byId = metrics.associateBy { it.activityId }
            activities.map { ActivityWithMetrics(it.toModel(), byId[it.id]?.toModel()) }
        }
    }

    fun observeActivitiesBetween(from: String, to: String): Flow<List<ActivityWithMetrics>> = holder.current.flatMapLatest { db ->
        combine(db.activityDao().observeBetween(from, to), db.metricsDao().observeAllActivityMetrics()) { activities, metrics ->
            val byId = metrics.associateBy { it.activityId }
            activities.map { ActivityWithMetrics(it.toModel(), byId[it.id]?.toModel()) }
        }
    }

    fun observeDetail(id: Long): Flow<ActivityDetail?> = holder.current.flatMapLatest { db ->
        combine(
            db.activityDao().observe(id),
            db.metricsDao().observeActivityMetrics(id),
            db.lapDao().observe(id),
            db.streamDao().observe(id)
        ) { activity, metrics, laps, streams ->
            activity?.let {
                ActivityDetail(
                    activity = it.toModel(),
                    metrics = metrics?.toModel(),
                    laps = laps.map { lap -> lap.toModel() },
                    streams = streams?.toModel()
                )
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WellnessRepository @Inject constructor(private val holder: DatabaseHolder) {
    fun observeLatest(): Flow<DailyWellness?> =
        holder.current.flatMapLatest { db -> db.wellnessDao().observeLatest().map { it?.toModel() } }

    fun observeBetween(from: String, to: String): Flow<List<DailyWellness>> =
        holder.current.flatMapLatest { db -> db.wellnessDao().observeBetween(from, to).map { list -> list.map { it.toModel() } } }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FitnessRepository @Inject constructor(private val holder: DatabaseHolder) {
    fun observeDailyLoads(from: String, to: String): Flow<List<DailyLoadPoint>> =
        holder.current.flatMapLatest { db -> db.metricsDao().observeDailyLoads(from, to).map { list -> list.map { it.toModel() } } }

    fun observeLatest(): Flow<DailyLoadPoint?> =
        holder.current.flatMapLatest { db -> db.metricsDao().observeLatestDailyLoad().map { it?.toModel() } }
}

fun DailyLoadEntity.toModel() = DailyLoadPoint(date = date, load = load, ctl = ctl, atl = atl, tsb = tsb, acwr = acwr)

fun ActivityMetricsEntity.toModel() = ActivityMetrics(
    activityId = activityId,
    load = load,
    loadMethod = when (loadMethod) {
        "rtss" -> LoadMethod.RTSS
        "trimp" -> LoadMethod.TRIMP
        else -> LoadMethod.DURATION
    },
    ngpMps = ngpMps,
    intensityFactor = intensityFactor,
    trimp = trimp,
    hrZoneSeconds = hrZoneSeconds?.split(',')?.mapNotNull { it.toDoubleOrNull() },
    efficiencyFactor = efficiencyFactor,
    decouplingPct = decouplingPct,
    vdot = vdot
)
