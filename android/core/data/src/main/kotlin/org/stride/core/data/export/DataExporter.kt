package org.stride.core.data.export

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.data.repository.toModel
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.database.toModel
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.Activity
import org.stride.core.model.ActivityMetrics
import org.stride.core.model.AthleteProfile
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.DailyWellness
import org.stride.core.model.Lap

/**
 * Settings → Export all data (MVP-021). The export holds the profile and what
 * is in the active database. It never contains the Garmin key, the access
 * token or the Anthropic key: those live in SecretStore, which the exporter
 * has no access to.
 */
@Serializable
data class StrideExport(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String,
    val demo: Boolean,
    val profile: AthleteProfile,
    val activities: List<ExportedActivity>,
    val wellness: List<DailyWellness>,
    val dailyLoads: List<DailyLoadPoint>
) {
    companion object {
        const val FORMAT = "stride-export"
        const val VERSION = 1
    }
}

@Serializable
data class ExportedActivity(val activity: Activity, val metrics: ActivityMetrics?, val laps: List<Lap>)

@Singleton
class DataExporter @Inject constructor(
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    @Dispatcher(StrideDispatchers.IO) private val io: CoroutineDispatcher
) {
    /** The export as pretty-printed JSON. */
    suspend fun exportJson(): String = withContext(io) { EXPORT_JSON.encodeToString(StrideExport.serializer(), export(holder.db)) }

    suspend fun export(db: StrideDatabase): StrideExport {
        val metrics = db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        val activities = db.activityDao().allChronological().map { entity ->
            ExportedActivity(
                activity = entity.toModel(),
                metrics = metrics[entity.id]?.toModel(),
                laps = db.lapDao().get(entity.id).map { it.toModel() }
            )
        }
        return StrideExport(
            exportedAt = clock.now().toString(),
            demo = holder.isDemo,
            profile = prefs.current().profile,
            activities = activities,
            wellness = db.wellnessDao().all().map { it.toModel() },
            dailyLoads = db.metricsDao().allDailyLoads().map { it.toModel() }
        )
    }

    companion object {
        val EXPORT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
