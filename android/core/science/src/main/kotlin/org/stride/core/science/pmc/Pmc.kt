package org.stride.core.science.pmc

import kotlin.math.exp
import kotlin.math.min
import kotlinx.serialization.Serializable
import org.stride.core.science.load.computeActivityLoad
import org.stride.core.science.model.Activity
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.AcwrPoint
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.DailyLoad
import org.stride.core.science.model.ExperienceLevel
import org.stride.core.science.model.PmcPoint
import org.stride.core.science.util.DateKeys
import org.stride.core.science.util.mean
import org.stride.core.science.util.toFixed

/**
 * Aggregates activities into one [DailyLoad] per day key (startDateLocal, else startDate, first 10 chars),
 * summing tss/duration/distance and keeping the best-ranked method; sorted by date. Values are unrounded.
 * Mirrors packages/core/src/science/pmc.ts#toDailyLoads.
 */
fun toDailyLoads(activities: List<Activity>, profile: AthleteProfile): List<DailyLoad> {
    val byDate = LinkedHashMap<String, DailyLoad>()
    for (activity in activities) {
        val load = computeActivityLoad(activity, profile)
        val date = DateKeys.toDateKey(activity.startDateLocal ?: activity.startDate)
        val existing = byDate[date]
        byDate[date] = if (existing == null) {
            DailyLoad(date, load.tss, load.durationSec, load.distanceM, load.method, listOf(activity.id))
        } else {
            existing.copy(
                tss = existing.tss + load.tss,
                durationSec = existing.durationSec + load.durationSec,
                distanceM = existing.distanceM + load.distanceM,
                activityIds = existing.activityIds + activity.id,
                method = if (load.method.rank > existing.method.rank) load.method else existing.method
            )
        }
    }
    return byDate.values.sortedBy { it.date }
}

/** PMC options; the defaults are the TS defaults. Mirrors packages/core/src/science/pmc.ts#PmcOptions. */
@Serializable
data class PmcOptions(
    val ctlDays: Double = 42.0,
    val atlDays: Double = 7.0,
    val seedCtl: Double = 0.0,
    val seedAtl: Double = 0.0,
    /** End the series on this day key: extends past (projection) or truncates before the last load. */
    val throughDate: String? = null
)

/**
 * Calendar range from the first entry to throughDate (extend OR truncate) when given, else the last entry.
 * Entries are taken in list order (not re-sorted). Mirrors pmc.ts#seriesEnd.
 */
private fun seriesDays(dailyLoads: List<DailyLoad>, throughDate: String?): List<String> {
    val start = dailyLoads.first().date
    val end = if (throughDate != null) DateKeys.toDateKey(throughDate) else dailyLoads.last().date
    return DateKeys.eachDay(start, end)
}

/**
 * Performance Management Chart: CTL/ATL are EWMAs with alpha = 1 - exp(-1/N); TSB is YESTERDAY's CTL - ATL.
 * Missing days count as 0 TSS; each point is rounded to 2 dp (state keeps full precision).
 * Mirrors packages/core/src/science/pmc.ts#buildPmcSeries.
 */
fun buildPmcSeries(dailyLoads: List<DailyLoad>, options: PmcOptions = PmcOptions()): List<PmcPoint> {
    if (dailyLoads.isEmpty()) return emptyList()
    val ctlAlpha = 1 - exp(-1 / options.ctlDays)
    val atlAlpha = 1 - exp(-1 / options.atlDays)
    val tssByDate = dailyLoads.associate { it.date to it.tss }
    var ctl = options.seedCtl
    var atl = options.seedAtl
    return seriesDays(dailyLoads, options.throughDate).map { date ->
        val prevCtl = ctl
        val prevAtl = atl
        val tss = tssByDate[date] ?: 0.0
        ctl = prevCtl + ctlAlpha * (tss - prevCtl)
        atl = prevAtl + atlAlpha * (tss - prevAtl)
        PmcPoint(date = date, ctl = toFixed(ctl, 2), atl = toFixed(atl, 2), tsb = toFixed(prevCtl - prevAtl, 2))
    }
}

/** Last point, or null. Mirrors pmc.ts#latestPmc. */
fun latestPmc(series: List<PmcPoint>): PmcPoint? = series.lastOrNull()

/** CTL change per week over the last [weeks] weeks (look-back clamped to the series), rounded to 2 dp. Mirrors pmc.ts#rampRatePerWeek. */
fun rampRatePerWeek(series: List<PmcPoint>, weeks: Int = 1): Double {
    if (series.size < 2) return 0.0
    val last = series.last()
    val daysBack = min(series.size - 1, weeks * 7)
    val prior = series[series.size - 1 - daysBack]
    val weeksElapsed = daysBack / 7.0
    return if (weeksElapsed > 0) toFixed((last.ctl - prior.ctl) / weeksElapsed, 2) else 0.0
}

/** ACWR options; the defaults are the TS defaults (warmupDays null = chronicDays). Mirrors acwr.ts#AcwrOptions. */
@Serializable
data class AcwrOptions(
    val acuteDays: Int = 7,
    val chronicDays: Int = 28,
    val warmupDays: Int? = null,
    val throughDate: String? = null
)

private fun flagFor(acwr: Double, chronic: Double): AcwrFlag = when {
    chronic < 1 -> AcwrFlag.OK
    acwr < 0.8 -> AcwrFlag.LOW
    acwr <= 1.3 -> AcwrFlag.OK
    acwr <= 1.5 -> AcwrFlag.HIGH
    else -> AcwrFlag.VERY_HIGH
}

/**
 * EWMA acute:chronic workload ratio, lambda = 2/(N+1). Both EWMAs are seeded with the mean TSS of the first
 * acuteDays CALENDAR DAYS (missing days = 0); flags are forced OK for the first warmupDays points and when chronic < 1 (flag uses
 * unrounded values). Points are rounded to 2 dp. Mirrors packages/core/src/science/acwr.ts#buildAcwrSeries.
 */
fun buildAcwrSeries(dailyLoads: List<DailyLoad>, options: AcwrOptions = AcwrOptions()): List<AcwrPoint> {
    if (dailyLoads.isEmpty()) return emptyList()
    val warmupDays = options.warmupDays ?: options.chronicDays
    val acuteLambda = 2.0 / (options.acuteDays + 1)
    val chronicLambda = 2.0 / (options.chronicDays + 1)
    val tssByDate = dailyLoads.associate { it.date to it.tss }
    val days = seriesDays(dailyLoads, options.throughDate)
    if (days.isEmpty()) return emptyList()
    // Seed = mean TSS over the first acuteDays CALENDAR days (rest days count as 0), never past the series end.
    val seed = mean(days.take(options.acuteDays).map { tssByDate[it] ?: 0.0 })
    var acute = seed
    var chronic = seed
    return days.mapIndexed { dayIndex, date ->
        val tss = tssByDate[date] ?: 0.0
        acute = tss * acuteLambda + (1 - acuteLambda) * acute
        chronic = tss * chronicLambda + (1 - chronicLambda) * chronic
        val acwr = if (chronic > 0) acute / chronic else 0.0
        AcwrPoint(
            date = date,
            acwr = toFixed(acwr, 2),
            acuteLoad = toFixed(acute, 2),
            chronicLoad = toFixed(chronic, 2),
            flag = if (dayIndex < warmupDays) AcwrFlag.OK else flagFor(acwr, chronic)
        )
    }
}

/** Last point, or null. Mirrors acwr.ts#latestAcwr. */
fun latestAcwr(series: List<AcwrPoint>): AcwrPoint? = series.lastOrNull()

/** Max CTL rise per week by experience (4 / 5.5 / 7). Mirrors packages/core/src/science/acwr.ts#ctlRampCap. */
fun ctlRampCap(experience: ExperienceLevel): Double = when (experience) {
    ExperienceLevel.BEGINNER -> 4.0
    ExperienceLevel.ADVANCED -> 7.0
    ExperienceLevel.INTERMEDIATE -> 5.5
}
