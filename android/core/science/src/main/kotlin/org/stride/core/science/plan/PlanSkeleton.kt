package org.stride.core.science.plan

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import org.stride.core.science.coach.DEFAULT_THRESHOLD_MPS
import org.stride.core.science.coach.QUALITY_TYPES
import org.stride.core.science.coach.makeSession
import org.stride.core.science.coach.resolveRaceDistanceM
import org.stride.core.science.coach.thresholdOf
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.PlanDay
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.PlanWeek
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.util.DateKeys
import org.stride.core.science.util.jsRound
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.truthy
import org.stride.core.science.vdot.thresholdSpeedFromVdot

/** Input to [buildPlanSkeleton]. Mirrors packages/core/src/coach/planner.ts#PlanSkeletonParams. */
@Serializable
data class PlanSkeletonParams(
    val profile: AthleteProfile,
    val goal: RaceGoal,
    val weeks: Int,
    /** ISO date or datetime; truncated to its YYYY-MM-DD key, which is day 1 (shown as "Monday") of week 1. */
    val startDate: String,
    val planId: String,
    /** ISO timestamp stored on the plan. */
    val createdAt: String,
    /** Recent training (MVP-025, see [fitnessSeed]); null = the fixed-minute skeleton. */
    val seed: FitnessSeed? = null
)

private fun phaseForWeek(week: Int, weeks: Int): PlanPhase {
    val taperWeeks = if (weeks >= 6) {
        2
    } else if (weeks >= 3) {
        1
    } else {
        0
    }
    val baseWeeks = max(1, jsRound(weeks * 0.35).toInt())
    val peakWeeks = if (weeks >= 6 && weeks - taperWeeks - baseWeeks >= 2) 1 else 0
    val buildEnd = weeks - taperWeeks - peakWeeks
    return when {
        week > weeks - taperWeeks -> PlanPhase.TAPER
        week > buildEnd -> PlanPhase.PEAK
        week > baseWeeks -> PlanPhase.BUILD
        else -> PlanPhase.BASE
    }
}

private val FOCUS = mapOf(
    PlanPhase.BASE to "Aerobic base — mostly easy volume, a light dose of intensity.",
    PlanPhase.BUILD to "Build — threshold and tempo work on top of maintained volume.",
    PlanPhase.PEAK to "Peak — race-specific intensity (VO2max/threshold), volume holds.",
    PlanPhase.TAPER to "Taper — cut volume ~50%, keep a little intensity, arrive fresh.",
    PlanPhase.RECOVERY to "Recovery week — reduced volume to absorb the last block."
)

/** Weekly focus text for a phase (the plan screen shows it). */
fun phaseFocus(phase: PlanPhase): String = FOCUS.getValue(phase)

/** One slot of a weekly template; [min] is ignored for LONG (the progression sets it) and REST. */
private data class TemplateSlot(val day: Int, val type: WorkoutType, val min: Double = 0.0)

/**
 * Weekly templates per phase (RECOVERY = the recovery week, whatever its phase). Unseeded plans use the minutes
 * as-is; seeded plans scale every non-long running session by one per-week factor. Mirrors planner.ts#TEMPLATES.
 */
private val TEMPLATES: Map<PlanPhase, List<TemplateSlot>> = run {
    val r = WorkoutType.REST
    val e = WorkoutType.EASY
    val l = WorkoutType.LONG
    mapOf(
        PlanPhase.RECOVERY to listOf(
            TemplateSlot(1, r),
            TemplateSlot(2, e, 35.0),
            TemplateSlot(3, e, 40.0),
            TemplateSlot(4, r),
            TemplateSlot(5, e, 30.0),
            TemplateSlot(6, r),
            TemplateSlot(7, l)
        ),
        PlanPhase.BUILD to listOf(
            TemplateSlot(1, r),
            TemplateSlot(2, WorkoutType.THRESHOLD, 50.0),
            TemplateSlot(3, e, 45.0),
            TemplateSlot(4, e, 40.0),
            TemplateSlot(5, r),
            TemplateSlot(6, WorkoutType.TEMPO, 40.0),
            TemplateSlot(7, l)
        ),
        PlanPhase.PEAK to listOf(
            TemplateSlot(1, r),
            TemplateSlot(2, WorkoutType.INTERVAL, 50.0),
            TemplateSlot(3, e, 40.0),
            TemplateSlot(4, WorkoutType.TEMPO, 40.0),
            TemplateSlot(5, r),
            TemplateSlot(6, e, 40.0),
            TemplateSlot(7, l)
        ),
        PlanPhase.TAPER to listOf(
            TemplateSlot(1, r),
            TemplateSlot(2, WorkoutType.THRESHOLD, 30.0),
            TemplateSlot(3, e, 30.0),
            TemplateSlot(4, r),
            TemplateSlot(5, e, 25.0),
            TemplateSlot(6, r),
            TemplateSlot(7, e, 40.0)
        ),
        PlanPhase.BASE to listOf(
            TemplateSlot(1, r),
            TemplateSlot(2, e, 45.0),
            TemplateSlot(3, e, 50.0),
            TemplateSlot(4, e, 40.0),
            TemplateSlot(5, r),
            TemplateSlot(6, e, 40.0),
            TemplateSlot(7, l)
        )
    )
}

/** Total template minutes of a week's non-long sessions. Mirrors planner.ts#templateNonLongMin. */
private fun templateNonLongMin(key: PlanPhase): Double =
    TEMPLATES.getValue(key).fold(0.0) { sum, s -> if (s.type == WorkoutType.LONG) sum else sum + s.min }

/** A week from its template: long = [longMin], other runs = min x [scale]. Mirrors planner.ts#weeklySessions. */
private fun weeklySessions(key: PlanPhase, longMin: Double, threshold: Double, scale: Double): List<PlanDay> =
    TEMPLATES.getValue(key).map { slot ->
        val min = when (slot.type) {
            WorkoutType.LONG -> longMin
            WorkoutType.REST -> 0.0
            else -> slot.min * scale
        }
        PlanDay(day = slot.day, sessions = listOf(makeSession(slot.type, min, threshold)))
    }

/** Seeded plans never start below this many minutes per week. */
const val SEED_MIN_WEEKLY_MIN = 60.0

/** Seeded long runs never start below this (unless the long-run share caps them). */
const val SEED_LONG_FLOOR_MIN = 40.0

/** A seeded long run is at most this share of its week (the guardrail allows 45%). */
const val SEED_LONG_SHARE = 0.4

/** Growth of volume and long run from one loading week to the next (at most +10%). */
const val SEED_WEEKLY_GROWTH = 1.08

/** Seeded volume grows to at most this multiple of the starting volume... */
const val SEED_MAX_GROWTH = 2.0

/** ...and never past this many minutes per week (unless the athlete already runs more). */
const val SEED_MAX_WEEKLY_MIN = 600.0

/** A recovery week's volume as a share of the last loading week. */
const val SEED_RECOVERY_FACTOR = 0.65

/** A taper week's volume as a share of the last loading week. */
const val SEED_TAPER_FACTOR = 0.5

private const val LONG_CAP_MIN = 140.0
private const val PEAK_LONG_CAP_MIN = 110.0

/** A week's long run (minutes) and the scale applied to its other sessions. */
private data class WeekShape(val longMin: Double, val scale: Double)

/**
 * Week shapes for a seeded plan (MVP-025): week 1 totals max(60, seed weekly minutes) with a long run of the
 * seed's longest run clamped to [40, min(140, 0.4 x volume)]; a later loading week grows volume x1.08 over the
 * last loading week in the same phase (holds it on a phase change) up to max(start, min(2 x start, 600)), and the
 * long run x1.08 within the share and 140 caps (110 in a peak week); recovery = 0.65 and taper = 0.5 x the last
 * loading volume. Mirrors planner.ts#seededShapes.
 */
private fun seededShapes(seed: FitnessSeed, weeks: Int): List<WeekShape> {
    val start = max(SEED_MIN_WEEKLY_MIN, seed.weeklyMinutes)
    val cap = max(start, min(start * SEED_MAX_GROWTH, SEED_MAX_WEEKLY_MIN))
    var loadVolume = start
    var loadLong = 0.0
    var loadPhase: PlanPhase? = null
    return (1..weeks).map { w ->
        val phase = phaseForWeek(w, weeks)
        val recovery = w % 4 == 0 && phase != PlanPhase.TAPER
        val key = if (recovery) PlanPhase.RECOVERY else phase
        val volume: Double
        var longMin: Double
        if (phase == PlanPhase.TAPER) {
            volume = loadVolume * SEED_TAPER_FACTOR
            longMin = 0.0
        } else if (recovery) {
            volume = loadVolume * SEED_RECOVERY_FACTOR
            longMin = min(loadLong, SEED_LONG_SHARE * volume)
        } else {
            if (loadPhase == null) {
                volume = start
                longMin = min(min(max(seed.longestRunMin, SEED_LONG_FLOOR_MIN), LONG_CAP_MIN), SEED_LONG_SHARE * volume)
            } else {
                val growth = if (phase == loadPhase) SEED_WEEKLY_GROWTH else 1.0
                volume = min(cap, loadVolume * growth)
                longMin = min(min(loadLong * SEED_WEEKLY_GROWTH, LONG_CAP_MIN), SEED_LONG_SHARE * volume)
            }
            if (phase == PlanPhase.PEAK) longMin = min(longMin, PEAK_LONG_CAP_MIN)
            loadVolume = volume
            loadLong = longMin
            loadPhase = phase
        }
        WeekShape(longMin, (volume - longMin) / templateNonLongMin(key))
    }
}

/** Threshold for a plan: the seed's recent VDOT when known, else the profile's. Mirrors planner.ts#planThreshold. */
internal fun planThreshold(profile: AthleteProfile, seed: FitnessSeed?): Double {
    val vdot = seed?.vdot
    return if (vdot.truthy()) thresholdSpeedFromVdot(vdot!!) else thresholdOf(profile)
}

/** Where a plan sits on the calendar once aligned to the goal race. Mirrors planner.ts#RaceAlignment. */
data class RaceAlignment(val startDate: String, val weeks: Int, val raceDate: String? = null)

/**
 * Aligns a plan so its last week ends on race day: keeps the requested length when the race is far enough
 * away, else the whole weeks that fit (at least 1); the start is derived backwards from the race. No goal date
 * (or one before the start) keeps the requested start and length. Mirrors planner.ts#alignToRace.
 */
fun alignToRace(startDate: String, weeks: Int, goal: RaceGoal): RaceAlignment {
    val start = DateKeys.toDateKey(startDate)
    val goalDate = goal.date ?: return RaceAlignment(start, weeks)
    val race = DateKeys.toDateKey(goalDate)
    val span = runCatching { DateKeys.daysBetween(start, race) + 1 }.getOrNull()
    if (span == null || span < 1) return RaceAlignment(start, weeks)
    val fit = max(1, min(weeks, span / 7))
    return RaceAlignment(DateKeys.addDays(race, -(fit * 7 - 1)), fit, race)
}

/** The race session over the goal distance at threshold speed. Mirrors planner.ts#raceSession. */
fun raceSession(goal: RaceGoal, thresholdSpeedMps: Double, date: String): WorkoutSuggestion {
    val speed = if (thresholdSpeedMps > 0) thresholdSpeedMps else DEFAULT_THRESHOLD_MPS
    val minutes = resolveRaceDistanceM(goal) / speed / 60
    val name = goal.name ?: goal.distance.wire
    return makeSession(
        WorkoutType.RACE,
        minutes,
        speed,
        date = date,
        rationale = "Race day: the taper has you fresh, so trust your training.",
        description = "Race day: $name. Warm up well, start controlled, and run your plan."
    )
}

/** Race on day 7; a hard day 6 becomes an easy run of the same length. Mirrors planner.ts#withRaceDay. */
private fun withRaceDay(days: List<PlanDay>, race: WorkoutSuggestion, threshold: Double): List<PlanDay> = days.map { d ->
    when {
        d.day == 7 -> d.copy(sessions = listOf(race.copy(date = d.date ?: race.date)))

        d.day == 6 && d.sessions.any { it.type in QUALITY_TYPES } -> {
            val min = (d.sessions.first().targetDurationSec ?: 0.0) / 60
            d.copy(sessions = listOf(makeSession(WorkoutType.EASY, min, threshold, date = d.date)))
        }

        else -> d
    }
}

private fun sessionsTotal(days: List<PlanDay>, field: (WorkoutSuggestion) -> Double?): Double =
    days.sumOf { d -> d.sessions.sumOf { field(it) ?: 0.0 } }

/**
 * With [PlanSkeletonParams.seed] the week volumes and long runs follow [seededShapes] and paces come from the
 * seed's VDOT when it has one (MVP-025).
 * Periodized plan: base (~35% of weeks) -> build -> peak (1 week when >= 6 weeks) -> taper (2 weeks when >= 6,
 * 1 when >= 3), with every 4th non-taper week a recovery week. The long run grows 8 min per loading week
 * (cap 140) and shrinks x0.6 (min 40) per taper week; it can be fractional and is rounded inside makeSession.
 * Week targetTss is the session sum to 0 dp, targetDistanceKm to 1 dp. Satisfies the guardrails by construction.
 * Mirrors packages/core/src/coach/planner.ts#buildPlanSkeleton.
 */
fun buildPlanSkeleton(params: PlanSkeletonParams): TrainingPlan {
    val threshold = planThreshold(params.profile, params.seed)
    val (start, weeks, raceDate) = alignToRace(params.startDate, params.weeks, params.goal)
    val shapes = params.seed?.let { seededShapes(it, weeks) }
    var longMin = 60.0
    val planWeeks = (1..weeks).map { w ->
        val phase = phaseForWeek(w, weeks)
        val recovery = w % 4 == 0 && phase != PlanPhase.TAPER
        val key = if (recovery) PlanPhase.RECOVERY else phase
        if (phase == PlanPhase.TAPER) {
            longMin = max(40.0, longMin * 0.6)
        } else if (!recovery) {
            longMin = min(LONG_CAP_MIN, longMin + 8)
        }
        val shape = shapes?.get(w - 1) ?: WeekShape(
            longMin = when {
                recovery -> min(longMin, 60.0)
                phase == PlanPhase.PEAK -> min(longMin, PEAK_LONG_CAP_MIN)
                else -> longMin
            },
            scale = 1.0
        )
        val dated = weeklySessions(key, shape.longMin, threshold, shape.scale).map { d ->
            val date = DateKeys.addDays(start, (w - 1) * 7 + (d.day - 1))
            d.copy(date = date, sessions = d.sessions.map { it.copy(date = date) })
        }
        val days = if (raceDate != null && w == weeks) {
            withRaceDay(dated, raceSession(params.goal, threshold, raceDate), threshold)
        } else {
            dated
        }
        PlanWeek(
            weekNumber = w,
            phase = if (recovery) PlanPhase.RECOVERY else phase,
            focus = if (recovery) FOCUS.getValue(PlanPhase.RECOVERY) else FOCUS.getValue(phase),
            targetTss = toFixed(sessionsTotal(days) { it.targetTss }, 0),
            targetDistanceKm = toFixed(sessionsTotal(days) { it.targetDistanceM } / 1000, 1),
            days = days
        )
    }
    val goalName = params.goal.name ?: params.goal.distance.wire
    return TrainingPlan(
        id = params.planId,
        createdAt = params.createdAt,
        goal = params.goal,
        startDate = start,
        endDate = DateKeys.addDays(start, weeks * 7 - 1),
        summary = "A $weeks-week $goalName plan: base → build → peak → taper, with a recovery week every 4th week.",
        weeks = planWeeks
    )
}
