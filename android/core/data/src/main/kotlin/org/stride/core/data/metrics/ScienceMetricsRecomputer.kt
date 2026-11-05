package org.stride.core.data.metrics

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.entity.ActivityMetricsEntity
import org.stride.core.database.entity.DailyLoadEntity
import org.stride.core.database.toModel
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.Activity as AppActivity
import org.stride.core.science.Science
import org.stride.core.science.anchors.applyEstimatedAnchors
import org.stride.core.science.anchors.estimateAnchors
import org.stride.core.science.anchors.resolveLthr
import org.stride.core.science.anchors.resolveMaxHr
import org.stride.core.science.anchors.resolveRestingHr
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.efficiency.aerobicDecoupling
import org.stride.core.science.efficiency.efficiencyFactor
import org.stride.core.science.load.banisterTrimp
import org.stride.core.science.load.computeActivityLoad
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.DailyLoad
import org.stride.core.science.model.LoadMethod
import org.stride.core.science.pmc.AcwrOptions
import org.stride.core.science.pmc.PmcOptions
import org.stride.core.science.pmc.buildAcwrSeries
import org.stride.core.science.pmc.buildPmcSeries
import org.stride.core.science.vdot.vdotFromEffort
import org.stride.core.science.zones.computeHrZones
import org.stride.core.science.zones.timeInHrZones

/**
 * The only place numbers are produced (MVP-012, compute-in-code): runs
 * core:science over Room and stores per-activity metrics plus the daily load /
 * PMC / ACWR series. Screens only read what this writes.
 *
 * - New or changed activities get load, HR zones, EF, decoupling and VDOT.
 * - The daily series is recomputed in memory (cheap) and rewritten from the
 *   earliest changed date onwards.
 * - A change in resolved anchors (profile edits or new estimates) or engine
 *   version triggers a full recompute.
 * - Runs on the Default dispatcher, serialised by a mutex, and is idempotent.
 */
@Singleton
class ScienceMetricsRecomputer @Inject constructor(
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    @Dispatcher(StrideDispatchers.Default) private val dispatcher: CoroutineDispatcher
) : MetricsRecomputer {
    private val mutex = Mutex()

    override suspend fun recompute(changedActivityIds: Set<Long>?, fromDate: String?) = withContext(dispatcher) {
        mutex.withLock { run(changedActivityIds, fromDate) }
    }

    /** Resolves the profile used for every metric: overrides first, then estimates from history. */
    suspend fun resolvedProfile(): AthleteProfile = withContext(dispatcher) {
        val activities = holder.db.activityDao().allChronological().map { it.toModel() }
        resolve(activities)
    }

    private suspend fun resolve(activities: List<AppActivity>): AthleteProfile {
        val today = clock.today()
        val base = prefs.current().profile.toScience(today)
        val estimated = estimateAnchors(activities.map { it.toScience(null, clock.zone()) })
        return applyEstimatedAnchors(base, estimated)
    }

    @Suppress("CyclomaticComplexMethod") // one linear pipeline; each branch is a nullable metric
    private suspend fun run(changedActivityIds: Set<Long>?, fromDate: String?) {
        val db = holder.db
        val activities = db.activityDao().allChronological().map { it.toModel() }
        if (activities.isEmpty()) {
            db.metricsDao().replaceDailyLoadsFrom("0000-01-01", emptyList())
            return
        }
        val profile = resolve(activities)
        val anchorsHash = anchorsHash(profile)
        val stored = db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        val stale = stored.values.any { it.anchorsHash != anchorsHash || it.engineVersion != Science.ENGINE_VERSION }
        val missing = activities.filter { it.id !in stored }.map { it.id }
        val toCompute: Set<Long> = if (stale || changedActivityIds == null) {
            activities.map { it.id }.toSet()
        } else {
            changedActivityIds + missing
        }
        val fullRebuild = stale || fromDate == null || changedActivityIds == null

        val hrZones = resolveLthr(profile)?.let { computeHrZones(it, resolveMaxHr(profile)) }
        val computed = activities.filter { it.id in toCompute }.map { activity ->
            val streams = if (activity.hasStreams) db.streamDao().get(activity.id)?.toModel() else null
            val sci = activity.toScience(streams, clock.zone())
            val load = computeActivityLoad(sci, profile)
            val hr = sci.streams?.heartrate
            val zoneSeconds = if (hr != null && hrZones != null) {
                timeInHrZones(hr, sci.streams?.time, hrZones).values.toList()
            } else {
                null
            }
            ActivityMetricsEntity(
                activityId = activity.id,
                load = load.tss,
                loadMethod = when (load.method) {
                    LoadMethod.RTSS -> "rtss"
                    LoadMethod.HRTSS, LoadMethod.TRIMP -> "trimp"
                    else -> "duration"
                },
                ngpMps = load.ngpSpeedMps,
                intensityFactor = load.intensityFactor,
                trimp = banisterTrimp(sci, profile),
                hrZoneSeconds = zoneSeconds?.joinToString(","),
                efficiencyFactor = efficiencyFactor(sci),
                decouplingPct = aerobicDecoupling(sci),
                vdot = if (activity.sport.isRun && activity.distanceM >= MIN_VDOT_DISTANCE_M) {
                    vdotFromEffort(activity.distanceM, activity.movingTimeSec ?: activity.durationSec)
                } else {
                    null
                },
                engineVersion = Science.ENGINE_VERSION,
                anchorsHash = anchorsHash
            )
        }

        // Daily loads from stored per-activity loads, bucketed by local date (mirrors toDailyLoads).
        val loadById = stored.mapValues { it.value.load } + computed.associate { it.activityId to it.load }
        val methodById = stored.mapValues { it.value.loadMethod } + computed.associate { it.activityId to it.loadMethod }
        val dailyLoads = activities.groupBy { it.toScience(null, clock.zone()).startDateLocal!!.take(DATE_KEY_LENGTH) }
            .toSortedMap()
            .map { (date, dayActivities) ->
                DailyLoad(
                    date = date,
                    tss = dayActivities.sumOf { loadById[it.id] ?: 0.0 },
                    durationSec = dayActivities.sumOf { it.movingTimeSec ?: it.durationSec },
                    distanceM = dayActivities.sumOf { it.distanceM },
                    method = bestMethod(dayActivities.mapNotNull { methodById[it.id] }),
                    activityIds = dayActivities.map { it.id.toString() }
                )
            }
        val today = clock.today().toString()
        val pmc = buildPmcSeries(dailyLoads, PmcOptions(throughDate = today))
        val acwrByDate = buildAcwrSeries(dailyLoads, AcwrOptions(throughDate = today)).associateBy { it.date }
        val tssByDate = dailyLoads.associate { it.date to it.tss }
        val writeFrom = if (fullRebuild) "0000-01-01" else minOf(fromDate!!, today)
        val rows = pmc.filter { it.date >= writeFrom }.map { p ->
            val acwr = acwrByDate[p.date]
            DailyLoadEntity(
                date = p.date,
                load = tssByDate[p.date] ?: 0.0,
                ctl = p.ctl,
                atl = p.atl,
                tsb = p.tsb,
                acuteLoad = acwr?.acuteLoad,
                chronicLoad = acwr?.chronicLoad,
                acwr = acwr?.acwr
            )
        }
        db.withTransaction {
            db.metricsDao().upsertActivityMetrics(computed)
            db.metricsDao().replaceDailyLoadsFrom(writeFrom, rows)
        }
    }

    private fun bestMethod(methods: List<String>): LoadMethod = when {
        "rtss" in methods -> LoadMethod.RTSS
        "trimp" in methods -> LoadMethod.TRIMP
        else -> LoadMethod.DURATION
    }

    companion object {
        private const val DATE_KEY_LENGTH = 10

        /** Efforts shorter than this make poor VDOT estimates. */
        const val MIN_VDOT_DISTANCE_M = 3000.0

        fun anchorsHash(profile: AthleteProfile): String = listOf(
            resolveLthr(profile),
            resolveMaxHr(profile),
            resolveRestingHr(profile),
            resolveThresholdSpeed(profile)
        ).joinToString("|") { v -> v?.let { "%.4f".format(java.util.Locale.ROOT, it) } ?: "-" }
    }
}
