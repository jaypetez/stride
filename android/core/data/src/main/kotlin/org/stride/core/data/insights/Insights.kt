package org.stride.core.data.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.metrics.toScience
import org.stride.core.data.repository.toModel
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.StrideDatabase
import org.stride.core.database.toModel
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.DailyLoadPoint
import org.stride.core.model.FormBand
import org.stride.core.science.anchors.estimateAnchors
import org.stride.core.science.anchors.resolveLthr
import org.stride.core.science.anchors.resolveMaxHr
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.coach.proposeNextWorkout
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.AcwrPoint
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.CoachContext
import org.stride.core.science.model.DailyLoad
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.LoadMethod
import org.stride.core.science.model.PaceZone
import org.stride.core.science.model.PmcPoint
import org.stride.core.science.model.RecentActivity
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.science.model.ZoneDistribution
import org.stride.core.science.pmc.AcwrOptions
import org.stride.core.science.pmc.buildAcwrSeries
import org.stride.core.science.pmc.rampRatePerWeek
import org.stride.core.science.vdot.predictRaceTimeSec
import org.stride.core.science.zones.computeHrZones
import org.stride.core.science.zones.computePaceZones
import org.stride.core.science.zones.zoneDistribution

/** Form band from TSB (GOAL.md §7): race-day form +5..+25, productive 0..−30, below −30 back off. */
fun formBand(tsb: Double): FormBand = when {
    tsb >= FRESH_MIN_TSB -> FormBand.FRESH
    tsb >= NEUTRAL_MIN_TSB -> FormBand.NEUTRAL
    tsb >= PRODUCTIVE_MIN_TSB -> FormBand.PRODUCTIVE
    else -> FormBand.OVERREACHING
}

const val FRESH_MIN_TSB = 5.0
const val NEUTRAL_MIN_TSB = -10.0
const val PRODUCTIVE_MIN_TSB = -30.0

/** Week-over-week volume. */
data class WeeklyVolume(val distanceM: Double, val durationSec: Double, val load: Double, val runs: Int)

data class TodayInsights(
    val today: String,
    val form: DailyLoadPoint?,
    val band: FormBand?,
    val acwr: Double?,
    val acwrFlag: AcwrFlag?,
    val thisWeek: WeeklyVolume,
    val lastWeek: WeeklyVolume,
    val nextWorkout: WorkoutSuggestion?
)

data class WeeklyZones(val weekStart: String, val distribution: ZoneDistribution)

data class VdotPoint(val weekStart: String, val vdot: Double)

data class RacePrediction(val label: String, val distanceM: Double, val timeSec: Double)

data class FitnessInsights(
    val pmc: List<DailyLoadPoint>,
    val weeklyZones: List<WeeklyZones>,
    val vdotTrend: List<VdotPoint>,
    val currentVdot: Double?,
    val predictions: List<RacePrediction>,
    val hrZones: List<HrZone>,
    val paceZones: List<PaceZone>,
    val rampRatePerWeek: Double?
)

/**
 * Read-side insights for Today and Fitness, computed by core:science from what
 * the recompute pipeline stored. Re-evaluated whenever the daily series changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InsightsRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    private val recomputer: ScienceMetricsRecomputer,
    @Dispatcher(StrideDispatchers.Default) private val dispatcher: CoroutineDispatcher
) {
    fun observeToday(): Flow<TodayInsights> = holder.current.flatMapLatest { db ->
        combine(db.metricsDao().observeLatestDailyLoad(), db.activityDao().observeAll(), prefs.settings) { _, _, _ -> db }
            .mapLatest { today(it) }
    }.flowOn(dispatcher)

    fun observeFitness(): Flow<FitnessInsights> = holder.current.flatMapLatest { db ->
        combine(db.metricsDao().observeLatestDailyLoad(), prefs.settings) { _, _ -> db }.mapLatest { fitness(it) }
    }.flowOn(dispatcher)

    private suspend fun dailyLoads(db: StrideDatabase): List<DailyLoad> = db.metricsDao().allDailyLoads()
        .filter { it.load > 0 }
        .map { DailyLoad(date = it.date, tss = it.load, durationSec = 0.0, distanceM = 0.0, method = LoadMethod.RTSS) }

    suspend fun today(db: StrideDatabase = holder.db): TodayInsights {
        val today = clock.today()
        val series = db.metricsDao().allDailyLoads().map { it.toModel() }
        val latest = series.lastOrNull { it.date <= today.toString() }
        val loads = dailyLoads(db)
        val acwr: AcwrPoint? =
            if (loads.isEmpty()) null else buildAcwrSeries(loads, AcwrOptions(throughDate = today.toString())).lastOrNull()
        val activities = db.activityDao().allChronological().map { it.toModel() }
        val metrics = db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun volume(from: LocalDate, to: LocalDate): WeeklyVolume {
            val inWeek = activities.filter { a ->
                val d = LocalDate.parse(a.toScience(null, clock.zone()).startDateLocal!!.take(10))
                !d.isBefore(from) && !d.isAfter(to) && a.sport.isRun
            }
            return WeeklyVolume(
                distanceM = inWeek.sumOf { it.distanceM },
                durationSec = inWeek.sumOf { it.movingTimeSec ?: it.durationSec },
                load = inWeek.sumOf { metrics[it.id]?.load ?: 0.0 },
                runs = inWeek.size
            )
        }
        val profile = recomputer.resolvedProfile()
        val next = if (activities.isEmpty()) {
            null
        } else {
            val recent = activities.asReversed().take(RECENT_COUNT).map { a ->
                val m = metrics[a.id]
                RecentActivity(
                    date = a.toScience(null, clock.zone()).startDateLocal!!.take(10),
                    name = a.name,
                    sportType = a.sport.toScience(),
                    distanceKm = a.distanceM / 1000.0,
                    durationSec = a.movingTimeSec ?: a.durationSec,
                    tss = m?.load ?: 0.0,
                    loadMethod = LoadMethod.RTSS,
                    avgHr = a.avgHr
                )
            }
            val weekActivities = activities.filter { it.startEpochSec >= clock.now().epochSecond - WEEK_SEC }
                .map { a -> a.toScience(if (a.hasStreams) db.streamDao().get(a.id)?.toModel() else null, clock.zone()) }
            val context = CoachContext(
                generatedAt = clock.now().toString(),
                profile = profile,
                fitness = latest?.let { PmcPoint(it.date, it.ctl, it.atl, it.tsb) },
                acwr = acwr,
                weeklyDistribution = if (weekActivities.isEmpty()) null else zoneDistribution(weekActivities, profile),
                recentActivities = recent
            )
            proposeNextWorkout(context, profile)
        }
        return TodayInsights(
            today = today.toString(),
            form = latest,
            band = latest?.let { formBand(it.tsb) },
            acwr = acwr?.acwr,
            acwrFlag = acwr?.flag,
            thisWeek = volume(weekStart, today),
            lastWeek = volume(weekStart.minusWeeks(1), weekStart.minusDays(1)),
            nextWorkout = next
        )
    }

    suspend fun fitness(db: StrideDatabase = holder.db): FitnessInsights {
        val today = clock.today()
        val pmc = db.metricsDao().allDailyLoads().map { it.toModel() }
        val activities = db.activityDao().allChronological().map { it.toModel() }
        val metrics = db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        val profile: AthleteProfile = recomputer.resolvedProfile()
        fun weekOf(a: org.stride.core.model.Activity): LocalDate =
            LocalDate.parse(a.toScience(null, clock.zone()).startDateLocal!!.take(10))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(ZONE_WEEKS - 1L)
        val runs = activities.filter { it.sport.isRun }
        val weeklyZones = runs.filter { !weekOf(it).isBefore(firstWeek) }.groupBy(::weekOf).toSortedMap().map { (week, list) ->
            val sci = list.map { a -> a.toScience(if (a.hasStreams) db.streamDao().get(a.id)?.toModel() else null, clock.zone()) }
            WeeklyZones(week.toString(), zoneDistribution(sci, profile))
        }
        val vdotTrend = runs.filter { metrics[it.id]?.vdot != null }.groupBy(::weekOf).toSortedMap().map { (week, list) ->
            VdotPoint(week.toString(), list.maxOf { metrics.getValue(it.id).vdot!! })
        }
        val estimated = estimateAnchors(activities.map { it.toScience(null, clock.zone()) }).vdot
        val currentVdot = profile.vdot ?: estimated
        val predictions = currentVdot?.let { v -> RACES.map { (label, m) -> RacePrediction(label, m, predictRaceTimeSec(v, m)) } }.orEmpty()
        val lthr = resolveLthr(profile)
        val threshold = resolveThresholdSpeed(profile)
        return FitnessInsights(
            pmc = pmc,
            weeklyZones = weeklyZones,
            vdotTrend = vdotTrend,
            currentVdot = currentVdot,
            predictions = predictions,
            hrZones = lthr?.let { computeHrZones(it, resolveMaxHr(profile)) }.orEmpty(),
            paceZones = threshold?.let { computePaceZones(it) }.orEmpty(),
            rampRatePerWeek = if (pmc.size > 7) rampRatePerWeek(pmc.map { PmcPoint(it.date, it.ctl, it.atl, it.tsb) }) else null
        )
    }

    companion object {
        private const val RECENT_COUNT = 14
        private const val WEEK_SEC = 7L * 24 * 3600
        private const val ZONE_WEEKS = 12
        val RACES = listOf("5K" to 5000.0, "10K" to 10_000.0, "Half marathon" to 21_097.5, "Marathon" to 42_195.0)
    }
}
