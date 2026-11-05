package org.stride.feature.coach

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.stride.core.data.insights.TodayInsights
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.model.ActivityDetail
import org.stride.core.model.UnitSystem

/*
 * The FACTS payload (MVP-019, ADR 0010): the only data the coach ever sends to
 * Anthropic. Every value is a number core:science already computed, rounded
 * for display. It never carries streams, laps, GPS, FIT data or Garmin
 * payloads; the builders below take what they need field by field.
 */

@Serializable
data class CoachFacts(
    /** "activity" or "fitness_trend". */
    val subject: String,
    /** "km" or "mi": distances and paces below use this unit. */
    @SerialName("distance_unit") val distanceUnit: String,
    val activity: ActivityFacts? = null,
    val fitness: FitnessFacts? = null
) {
    fun toJson(): String = FACTS_JSON.encodeToString(serializer(), this)

    companion object {
        const val SUBJECT_ACTIVITY = "activity"
        const val SUBJECT_TREND = "fitness_trend"
    }
}

@Serializable
data class ActivityFacts(
    val date: String,
    val sport: String,
    val distance: Double,
    @SerialName("duration_min") val durationMin: Double,
    /** Average pace as m:ss per [CoachFacts.distanceUnit]. */
    @SerialName("avg_pace") val avgPace: String? = null,
    @SerialName("avg_hr_bpm") val avgHr: Double? = null,
    @SerialName("max_hr_bpm") val maxHr: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    val load: Double? = null,
    @SerialName("load_method") val loadMethod: String? = null,
    @SerialName("intensity_factor") val intensityFactor: Double? = null,
    /** Minutes in heart-rate zones 1..5. */
    @SerialName("hr_zone_minutes") val hrZoneMinutes: List<Double>? = null,
    val vdot: Double? = null,
    @SerialName("efficiency_factor") val efficiencyFactor: Double? = null,
    @SerialName("aerobic_decoupling_pct") val decouplingPct: Double? = null
)

@Serializable
data class FitnessFacts(
    val date: String,
    @SerialName("fitness_ctl") val ctl: Double? = null,
    @SerialName("fatigue_atl") val atl: Double? = null,
    @SerialName("form_tsb") val tsb: Double? = null,
    @SerialName("form_band") val formBand: String? = null,
    val acwr: Double? = null,
    @SerialName("acwr_flag") val acwrFlag: String? = null,
    @SerialName("this_week") val thisWeek: WeekFacts,
    @SerialName("last_week") val lastWeek: WeekFacts,
    val vdot: Double? = null
)

@Serializable
data class WeekFacts(
    val distance: Double,
    @SerialName("duration_min") val durationMin: Double,
    val load: Double,
    val runs: Int
)

val FACTS_JSON = Json {
    explicitNulls = false
    encodeDefaults = true
}

private const val METRES_PER_MILE = 1609.344

private fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0

private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

private fun distanceIn(metres: Double, units: UnitSystem) = round2(
    if (units ==
        UnitSystem.IMPERIAL
    ) {
        metres / METRES_PER_MILE
    } else {
        metres / 1000.0
    }
)

private fun paceOf(speedMps: Double?, units: UnitSystem): String? {
    if (speedMps == null || speedMps <= 0) return null
    val secs = ((if (units == UnitSystem.IMPERIAL) METRES_PER_MILE else 1000.0) / speedMps).roundToLong()
    return "%d:%02d".format(java.util.Locale.ROOT, secs / 60, secs % 60)
}

fun unitLabel(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "mi" else "km"

/** Facts about one activity: its summary and computed metrics only (laps and streams are ignored). */
fun buildActivityFacts(detail: ActivityDetail, units: UnitSystem, zone: ZoneId): CoachFacts {
    val a = detail.activity
    val m = detail.metrics
    val tz = a.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
    return CoachFacts(
        subject = CoachFacts.SUBJECT_ACTIVITY,
        distanceUnit = unitLabel(units),
        activity = ActivityFacts(
            date = Instant.ofEpochSecond(a.startEpochSec).atZone(tz).toLocalDate().toString(),
            sport = a.sport.label,
            distance = distanceIn(a.distanceM, units),
            durationMin = round1((a.movingTimeSec ?: a.durationSec) / 60.0),
            avgPace = if (a.sport.isRun) paceOf(a.avgSpeedMps, units) else null,
            avgHr = a.avgHr?.let(::round1),
            maxHr = a.maxHr?.let(::round1),
            elevationGainM = a.elevationGainM?.let(::round1),
            load = m?.load?.let(::round1),
            loadMethod = m?.loadMethod?.label,
            intensityFactor = m?.intensityFactor?.let(::round2),
            hrZoneMinutes = m?.hrZoneSeconds?.map { round1(it / 60.0) },
            vdot = m?.vdot?.let(::round1),
            efficiencyFactor = m?.efficiencyFactor?.let { round2(it) },
            decouplingPct = m?.decouplingPct?.let(::round1)
        )
    )
}

/** Facts about the current fitness trend (PMC, ACWR, weekly volume, VDOT). */
fun buildTrendFacts(today: TodayInsights, vdot: Double?, units: UnitSystem): CoachFacts {
    fun week(v: WeeklyVolume) = WeekFacts(distanceIn(v.distanceM, units), round1(v.durationSec / 60.0), round1(v.load), v.runs)
    val form = today.form
    return CoachFacts(
        subject = CoachFacts.SUBJECT_TREND,
        distanceUnit = unitLabel(units),
        fitness = FitnessFacts(
            date = today.today,
            ctl = form?.ctl?.let(::round1),
            atl = form?.atl?.let(::round1),
            tsb = form?.tsb?.let(::round1),
            formBand = today.band?.label,
            acwr = today.acwr?.let(::round2),
            acwrFlag = today.acwrFlag?.name?.lowercase(),
            thisWeek = week(today.thisWeek),
            lastWeek = week(today.lastWeek),
            vdot = vdot?.let(::round1)
        )
    )
}

/** Human-readable lines of what will be sent, for the "What Stride sends" card. */
fun CoachFacts.describe(): List<Pair<String, String>> = buildList {
    fun add(label: String, value: Any?) {
        if (value != null) add(label to value.toString())
    }
    activity?.let { a ->
        add("Date", a.date)
        add("Sport", a.sport)
        add("Distance", "${a.distance} $distanceUnit")
        add("Duration", "${a.durationMin} min")
        add("Average pace", a.avgPace?.let { "$it /$distanceUnit" })
        add("Average HR", a.avgHr?.let { "$it bpm" })
        add("Training load", a.load?.let { "$it (${a.loadMethod})" })
        add("Intensity factor", a.intensityFactor)
        add("HR zone minutes", a.hrZoneMinutes?.joinToString(" / "))
        add("VDOT", a.vdot)
        add("Efficiency factor", a.efficiencyFactor)
        add("Aerobic decoupling", a.decouplingPct?.let { "$it%" })
    }
    fitness?.let { f ->
        add("Date", f.date)
        add("Fitness (CTL)", f.ctl)
        add("Fatigue (ATL)", f.atl)
        add("Form (TSB)", f.tsb?.let { t -> "$t${f.formBand?.let { " · $it" }.orEmpty()}" })
        add("Acute:chronic ratio", f.acwr)
        add("This week", "${f.thisWeek.distance} $distanceUnit · ${f.thisWeek.runs} runs · load ${f.thisWeek.load}")
        add("Last week", "${f.lastWeek.distance} $distanceUnit · ${f.lastWeek.runs} runs · load ${f.lastWeek.load}")
        add("VDOT", f.vdot)
    }
}
