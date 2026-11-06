package org.stride.core.data.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import org.stride.core.science.coach.resolveRaceDistanceM
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.GuardrailViolation
import org.stride.core.science.model.PlanKind
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.plan.BUILD_MAX_WEEKS
import org.stride.core.science.plan.BUILD_MIN_WEEKS
import org.stride.core.science.plan.FitnessSeed
import org.stride.core.science.plan.PlanGuardrailContext
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.repairPlan
import org.stride.core.science.plan.validatePlan

const val MIN_PLAN_WEEKS = 4
const val MAX_PLAN_WEEKS = 24

/** The build-plan length the form starts at (MVP-026). */
const val DEFAULT_BUILD_WEEKS = 8

/**
 * What the plan form collects (MVP-018). A [PlanKind.RACE] plan needs a [raceDate]; a [PlanKind.BUILD] plan
 * (MVP-026, no race) ignores the race fields and lasts [weeks] ([BUILD_MIN_WEEKS]..[BUILD_MAX_WEEKS]).
 */
data class PlanRequest(
    val distance: RaceDistance,
    val raceDate: LocalDate?,
    val goalTimeSec: Double? = null,
    val kind: PlanKind = PlanKind.RACE,
    val weeks: Int = DEFAULT_BUILD_WEEKS
) {
    companion object {
        /** A no-race "build my fitness" request of [weeks] weeks. */
        fun build(weeks: Int): PlanRequest = PlanRequest(RaceDistance.TEN_K, raceDate = null, kind = PlanKind.BUILD, weeks = weeks)
    }
}

sealed interface PlanResult {
    /**
     * Passed validatePlan, possibly after repairPlan applied [repairs]. [seed] is the recent training the plan
     * was seeded from (MVP-025), null when there were fewer than 3 recent runs.
     */
    data class Created(val plan: TrainingPlan, val repairs: List<GuardrailViolation>, val seed: FitnessSeed? = null) : PlanResult

    /** Still violates the guardrails after repair; nothing is saved. */
    data class Rejected(val violations: List<GuardrailViolation>) : PlanResult

    /** The request itself is unusable (e.g. the race is before the plan could start). */
    data class Invalid(val message: String) : PlanResult
}

/** Plans start on the Monday after [today] (a Monday also moves to the next one). */
fun planStartDate(today: LocalDate): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

/**
 * The earliest race date: the Sunday that ends the first plan week. Plans end ON race day
 * (core:science aligns the last week to it), so the race needs at least one full week after [planStartDate].
 */
fun earliestRaceDate(today: LocalDate): LocalDate = planStartDate(today).plusDays(6)

/**
 * Whole weeks from [start] through the race week, clamped to [MIN_PLAN_WEEKS]..[MAX_PLAN_WEEKS]. This is the
 * REQUESTED length: core:science shrinks it to the whole weeks that fit before the race.
 */
fun planWeeks(start: LocalDate, raceDate: LocalDate): Int =
    (ChronoUnit.DAYS.between(start, raceDate) / 7 + 1).toInt().coerceIn(MIN_PLAN_WEEKS, MAX_PLAN_WEEKS)

/** Race distance in metres for a request (core:science's mapping). */
fun raceDistanceM(distance: RaceDistance): Double = resolveRaceDistanceM(RaceGoal(distance = distance))

/**
 * Builds a plan with core:science's skeleton, then validates it against the
 * guardrails and repairs it once if needed. Only a plan that passes
 * [validatePlan] with [ctx] is returned as [PlanResult.Created].
 *
 * The skeleton is aligned to the race: its last week ends on race day with a race
 * session, so for a race that is not on a Sunday the first week starts a few days
 * after the Monday from [planStartDate].
 *
 * With a [seed] (MVP-025, from `fitnessSeed` over recent activities) the plan starts from the athlete's recent
 * weekly volume and long run, and paces from their recent VDOT; without one it is the fixed-minute skeleton.
 *
 * A [PlanKind.BUILD] request (MVP-026) needs no race date: it starts on [planStartDate], lasts
 * [PlanRequest.weeks] (4..16, else [PlanResult.Invalid]) and has base then build phases with no race session.
 */
fun generatePlan(
    request: PlanRequest,
    profile: AthleteProfile,
    today: LocalDate,
    ctx: PlanGuardrailContext,
    planId: String,
    createdAt: String,
    seed: FitnessSeed? = null
): PlanResult {
    requestProblem(request, today)?.let { return PlanResult.Invalid(it) }
    val start = planStartDate(today)
    val raceDate = request.raceDate
    val params = if (request.kind == PlanKind.BUILD || raceDate == null) {
        PlanSkeletonParams(
            profile = profile,
            goal = RaceGoal(distance = request.distance),
            weeks = request.weeks,
            startDate = start.toString(),
            planId = planId,
            createdAt = createdAt,
            seed = seed,
            kind = PlanKind.BUILD
        )
    } else {
        PlanSkeletonParams(
            profile = profile,
            goal = RaceGoal(distance = request.distance, date = raceDate.toString(), goalTimeSec = request.goalTimeSec),
            weeks = planWeeks(start, raceDate),
            startDate = start.toString(),
            planId = planId,
            createdAt = createdAt,
            seed = seed
        )
    }
    val skeleton = buildPlanSkeleton(params)
    if (validatePlan(skeleton, ctx).valid) return PlanResult.Created(skeleton, emptyList(), seed)
    val repaired = repairPlan(skeleton, ctx)
    val check = validatePlan(repaired.plan, ctx)
    return if (check.valid) PlanResult.Created(repaired.plan, repaired.fixed, seed) else PlanResult.Rejected(check.violations)
}

/** Why [request] cannot become a plan as of [today], or null when it can. */
private fun requestProblem(request: PlanRequest, today: LocalDate): String? {
    val earliest = earliestRaceDate(today)
    val raceDate = request.raceDate
    return when {
        request.kind == PlanKind.BUILD -> if (request.weeks in BUILD_MIN_WEEKS..BUILD_MAX_WEEKS) {
            null
        } else {
            "A fitness build lasts $BUILD_MIN_WEEKS to $BUILD_MAX_WEEKS weeks."
        }

        raceDate == null || raceDate.isBefore(earliest) ->
            "Pick a race date on or after $earliest, so the plan has at least one full week."

        request.goalTimeSec != null && request.goalTimeSec <= 0 -> "Goal time must be positive."

        else -> null
    }
}
