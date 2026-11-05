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
    val createdAt: String
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

private fun weeklySessions(phase: PlanPhase, recovery: Boolean, longMin: Double, threshold: Double): List<PlanDay> {
    fun day(d: Int, type: WorkoutType, min: Double) = PlanDay(day = d, sessions = listOf(makeSession(type, min, threshold)))
    fun rest(d: Int) = day(d, WorkoutType.REST, 0.0)
    val e = WorkoutType.EASY
    if (recovery) {
        return listOf(
            rest(1),
            day(2, e, 35.0),
            day(3, e, 40.0),
            rest(4),
            day(5, e, 30.0),
            rest(6),
            day(7, WorkoutType.LONG, min(longMin, 60.0))
        )
    }
    return when (phase) {
        PlanPhase.BUILD -> listOf(
            rest(1),
            day(2, WorkoutType.THRESHOLD, 50.0),
            day(3, e, 45.0),
            day(4, e, 40.0),
            rest(5),
            day(6, WorkoutType.TEMPO, 40.0),
            day(7, WorkoutType.LONG, longMin)
        )

        PlanPhase.PEAK -> listOf(
            rest(1),
            day(2, WorkoutType.INTERVAL, 50.0),
            day(3, e, 40.0),
            day(4, WorkoutType.TEMPO, 40.0),
            rest(5),
            day(6, e, 40.0),
            day(7, WorkoutType.LONG, min(longMin, 110.0))
        )

        PlanPhase.TAPER -> listOf(
            rest(1),
            day(2, WorkoutType.THRESHOLD, 30.0),
            day(3, e, 30.0),
            rest(4),
            day(5, e, 25.0),
            rest(6),
            day(7, e, 40.0)
        )

        else -> listOf(
            rest(1),
            day(2, e, 45.0),
            day(3, e, 50.0),
            day(4, e, 40.0),
            rest(5),
            day(6, e, 40.0),
            day(7, WorkoutType.LONG, longMin)
        )
    }
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
 * Periodized plan: base (~35% of weeks) -> build -> peak (1 week when >= 6 weeks) -> taper (2 weeks when >= 6,
 * 1 when >= 3), with every 4th non-taper week a recovery week. The long run grows 8 min per loading week
 * (cap 140) and shrinks x0.6 (min 40) per taper week; it can be fractional and is rounded inside makeSession.
 * Week targetTss is the session sum to 0 dp, targetDistanceKm to 1 dp. Satisfies the guardrails by construction.
 * Mirrors packages/core/src/coach/planner.ts#buildPlanSkeleton.
 */
fun buildPlanSkeleton(params: PlanSkeletonParams): TrainingPlan {
    val threshold = thresholdOf(params.profile)
    val (start, weeks, raceDate) = alignToRace(params.startDate, params.weeks, params.goal)
    var longMin = 60.0
    val planWeeks = (1..weeks).map { w ->
        val phase = phaseForWeek(w, weeks)
        val recovery = w % 4 == 0 && phase != PlanPhase.TAPER
        if (phase == PlanPhase.TAPER) {
            longMin = max(40.0, longMin * 0.6)
        } else if (!recovery) {
            longMin = min(140.0, longMin + 8)
        }
        val dated = weeklySessions(phase, recovery, longMin, threshold).map { d ->
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
