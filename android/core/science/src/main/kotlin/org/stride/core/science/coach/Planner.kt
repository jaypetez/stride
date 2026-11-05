package org.stride.core.science.coach

import kotlin.math.sqrt
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.CoachContext
import org.stride.core.science.model.IntensityLabel
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.util.jsRound
import org.stride.core.science.util.jsString
import org.stride.core.science.util.mpsToSecPerKm
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.truthy
import org.stride.core.science.vdot.thresholdSpeedFromVdot

/* Deterministic session maker and next-workout suggestion. Mirrors packages/core/src/coach/planner.ts. */

/** Threshold speed used when the athlete has no anchors (~5:33/km). */
const val DEFAULT_THRESHOLD_MPS = 3.0

/** Race distance in metres (custom falls back to 10 km). Mirrors planner.ts#resolveRaceDistanceM. */
fun resolveRaceDistanceM(goal: RaceGoal): Double = when (goal.distance) {
    RaceDistance.FIVE_K -> 5000.0
    RaceDistance.TEN_K -> 10_000.0
    RaceDistance.HALF -> 21_097.0
    RaceDistance.MARATHON -> 42_195.0
    RaceDistance.CUSTOM -> goal.customDistanceM ?: 10_000.0
}

/** Profile threshold, else from VDOT, else [DEFAULT_THRESHOLD_MPS]. Mirrors planner.ts#thresholdOf. */
internal fun thresholdOf(profile: AthleteProfile): Double = resolveThresholdSpeed(profile)
    ?: if (profile.vdot.truthy()) thresholdSpeedFromVdot(profile.vdot!!) else DEFAULT_THRESHOLD_MPS

private data class Intensity(val overallIf: Double, val paceIf: Double, val hrZone: Int, val label: IntensityLabel?)

private val INTENSITY = mapOf(
    WorkoutType.EASY to Intensity(0.68, 0.75, 2, IntensityLabel.E),
    WorkoutType.LONG to Intensity(0.7, 0.72, 2, IntensityLabel.E),
    WorkoutType.RECOVERY to Intensity(0.6, 0.65, 1, IntensityLabel.E),
    WorkoutType.TEMPO to Intensity(0.85, 0.9, 3, IntensityLabel.M),
    WorkoutType.THRESHOLD to Intensity(0.88, 0.97, 4, IntensityLabel.T),
    WorkoutType.INTERVAL to Intensity(0.92, 1.02, 5, IntensityLabel.I),
    WorkoutType.REPETITION to Intensity(0.95, 1.1, 5, IntensityLabel.R),
    WorkoutType.RACE to Intensity(1.0, 1.0, 5, IntensityLabel.T),
    WorkoutType.REST to Intensity(0.0, 0.0, 1, null),
    WorkoutType.CROSS_TRAINING to Intensity(0.5, 0.0, 1, null)
)

/** Session types that count as "hard" (quality) days for the 48 h rule. Mirrors planner.ts#QUALITY_TYPES. */
val QUALITY_TYPES: Set<WorkoutType> =
    setOf(WorkoutType.TEMPO, WorkoutType.THRESHOLD, WorkoutType.INTERVAL, WorkoutType.REPETITION, WorkoutType.RACE)

private val TITLES = mapOf(
    WorkoutType.EASY to "Easy run",
    WorkoutType.LONG to "Long run",
    WorkoutType.RECOVERY to "Recovery run",
    WorkoutType.TEMPO to "Tempo run",
    WorkoutType.THRESHOLD to "Threshold session",
    WorkoutType.INTERVAL to "Interval session",
    WorkoutType.REPETITION to "Repetition / strides",
    WorkoutType.RACE to "Race",
    WorkoutType.REST to "Rest day",
    WorkoutType.CROSS_TRAINING to "Cross-training"
)

/**
 * Builds one session. durationSec = round(min x 60); targetTss = IF^2 x hours x 100 to 0 dp (overall IF);
 * pace = round(1000 / (threshold x pace IF)) s/km and distance = round(speed x durationSec), both computed only
 * when speed > 0 (pace omitted and distance 0 otherwise). REST sessions carry no pace/zone/label.
 * Mirrors packages/core/src/coach/planner.ts#makeSession (TS `opts` are the named trailing parameters).
 */
fun makeSession(
    type: WorkoutType,
    durationMin: Double,
    thresholdSpeedMps: Double,
    date: String? = null,
    rationale: String? = null,
    description: String? = null
): WorkoutSuggestion {
    val it = INTENSITY.getValue(type)
    if (type == WorkoutType.REST) {
        return WorkoutSuggestion(
            type = type,
            title = TITLES.getValue(WorkoutType.REST),
            description = description ?: "Full rest. Recovery is when adaptation happens.",
            date = date,
            targetDurationSec = 0.0,
            targetTss = 0.0,
            rationale = rationale ?: "Rest days let fitness consolidate and reduce injury risk."
        )
    }
    val speed = thresholdSpeedMps * it.paceIf
    val durationSec = jsRound(durationMin * 60)
    val tss = toFixed(it.overallIf * it.overallIf * (durationMin / 60) * 100, 0)
    val pace = if (speed > 0) jsRound(mpsToSecPerKm(speed)) else null
    val hasPace = pace != null && pace.isFinite()
    return WorkoutSuggestion(
        type = type,
        label = it.label,
        title = "${TITLES.getValue(type)} (${jsString(jsRound(durationMin))} min)",
        description = description ?: describeType(type, durationMin),
        date = date,
        targetDistanceM = if (hasPace) jsRound(speed * durationSec) else 0.0,
        targetDurationSec = durationSec,
        targetPaceSecPerKm = if (hasPace) pace else null,
        targetHrZone = it.hrZone,
        targetTss = tss,
        rationale = rationale ?: defaultRationale(type)
    )
}

/**
 * The athlete's threshold speed recovered from a session's target pace (inverting pace IF), else
 * [DEFAULT_THRESHOLD_MPS]. Mirrors planner.ts#thresholdFromSession.
 */
fun thresholdFromSession(session: WorkoutSuggestion): Double {
    val paceIf = INTENSITY.getValue(session.type).paceIf
    val pace = session.targetPaceSecPerKm
    return if (pace.truthy() && paceIf > 0) 1000 / pace!! / paceIf else DEFAULT_THRESHOLD_MPS
}

/** Rebuilds [session] at a new duration, keeping date and rationale (REST unchanged). Mirrors planner.ts#rescaleSession. */
fun rescaleSession(session: WorkoutSuggestion, durationMin: Double): WorkoutSuggestion {
    if (session.type == WorkoutType.REST) return session
    return makeSession(session.type, durationMin, thresholdFromSession(session), date = session.date, rationale = session.rationale)
}

private fun describeType(type: WorkoutType, durationMin: Double): String {
    val m = jsString(jsRound(durationMin))
    return when (type) {
        WorkoutType.EASY -> "$m min at a conversational, aerobic pace."

        WorkoutType.LONG -> "$m min steady long run to build aerobic durability."

        WorkoutType.RECOVERY -> "$m min very easy to promote recovery."

        WorkoutType.TEMPO -> "Warm up, then a sustained comfortably-hard tempo block, then cool down (~$m min total)."

        WorkoutType.THRESHOLD ->
            "Warm up, then threshold intervals (e.g. 3-4 × 8 min at T pace, 90s jog), then cool down (~$m min total)."

        WorkoutType.INTERVAL ->
            "Warm up, then VO2max intervals (e.g. 5-6 × 3 min hard, equal jog recovery), then cool down (~$m min total)."

        WorkoutType.REPETITION -> "Warm up, then short fast reps / strides for economy (e.g. 8-10 × 30s), then cool down."

        else -> "$m min session."
    }
}

private fun defaultRationale(type: WorkoutType): String = if (type in QUALITY_TYPES) {
    "Targeted intensity to drive the specific adaptation for this phase; keep the hard parts controlled."
} else {
    "Most weekly volume should be easy (the ~80/20 principle) to build aerobic fitness while staying fresh."
}

/** `Number(n.toFixed(0))` rendered the way a JS template literal prints it. */
private fun roundText(n: Double): String = jsString(toFixed(n, 0))

private fun impliedIntensity(tss: Double, durationSec: Double): Double {
    val hours = durationSec / 3600
    if (hours <= 0) return 0.0
    return sqrt(tss / (hours * 100))
}

/**
 * Next session from the coach context, first match wins:
 * 1. ACWR very high or TSB < -25 -> recovery 30 min (back off);
 * 2. last activity (recentActivities[0]) implied IF > 0.82 -> easy 45 min (~48 h between quality days);
 * 3. hard share < 18% and TSB > -12 -> threshold 50 min (interval in the peak phase);
 * 4. no run >= 14 km in recentActivities and TSB > -10 -> long 80 min;
 * 5. otherwise easy 50 min.
 * Missing fitness/ACWR/distribution count as TSB 0, flag OK, hard 0%.
 * Mirrors packages/core/src/coach/planner.ts#proposeNextWorkout.
 */
@Suppress("ReturnCount") // One return per rule; the rule order IS the algorithm.
fun proposeNextWorkout(context: CoachContext, profile: AthleteProfile): WorkoutSuggestion {
    val threshold = thresholdOf(profile)
    val tsb = context.fitness?.tsb ?: 0.0
    val acwrFlag = context.acwr?.flag ?: AcwrFlag.OK
    val hardPct = context.weeklyDistribution?.hardPct ?: 0.0
    val last = context.recentActivities.firstOrNull()
    val lastHard = last != null && impliedIntensity(last.tss, last.durationSec) > 0.82
    val hasRecentLong = context.recentActivities.any { it.distanceKm >= 14 }

    if (acwrFlag == AcwrFlag.VERY_HIGH || tsb < -25) {
        return makeSession(
            WorkoutType.RECOVERY,
            30.0,
            threshold,
            rationale = "Your form (TSB ${roundText(tsb)}) and workload signals say back off — " +
                "an easy recovery run keeps things ticking without adding fatigue."
        )
    }
    if (lastHard) {
        return makeSession(
            WorkoutType.EASY,
            45.0,
            threshold,
            rationale = "Your last session was hard, so keep ~48h between quality days: " +
                "an easy run aids recovery and preserves the 80/20 balance."
        )
    }
    if (hardPct < 18 && tsb > -12) {
        val type = if (context.planPhase == PlanPhase.PEAK) WorkoutType.INTERVAL else WorkoutType.THRESHOLD
        val name = if (type == WorkoutType.INTERVAL) "interval" else "threshold"
        return makeSession(
            type,
            50.0,
            threshold,
            rationale = "You're reasonably fresh (TSB ${roundText(tsb)}) and only ${roundText(hardPct)}% of this week has been hard — " +
                "a $name session nudges you toward the ~20% quality target."
        )
    }
    if (!hasRecentLong && tsb > -10) {
        return makeSession(
            WorkoutType.LONG,
            80.0,
            threshold,
            rationale = "No long run in the last week — a steady long run develops aerobic durability, " +
                "the backbone of distance fitness."
        )
    }
    return makeSession(
        WorkoutType.EASY,
        50.0,
        threshold,
        rationale = "An easy aerobic run fits your current form (TSB ${roundText(tsb)}) and keeps the week's " +
            "intensity distribution balanced."
    )
}
