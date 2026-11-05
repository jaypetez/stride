package org.stride.core.science.plan

import kotlin.math.floor
import kotlin.math.max
import kotlinx.serialization.Serializable
import org.stride.core.science.coach.DEFAULT_THRESHOLD_MPS
import org.stride.core.science.coach.QUALITY_TYPES
import org.stride.core.science.coach.makeSession
import org.stride.core.science.coach.rescaleSession
import org.stride.core.science.coach.thresholdFromSession
import org.stride.core.science.model.DailyLoad
import org.stride.core.science.model.ExperienceLevel
import org.stride.core.science.model.GuardrailViolation
import org.stride.core.science.model.LoadMethod
import org.stride.core.science.model.PlanDay
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.PlanValidation
import org.stride.core.science.model.PlanWeek
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutSuggestion
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.pmc.PmcOptions
import org.stride.core.science.pmc.buildPmcSeries
import org.stride.core.science.pmc.ctlRampCap
import org.stride.core.science.util.DateKeys
import org.stride.core.science.util.jsRound
import org.stride.core.science.util.jsString
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.toFixedString

/* Deterministic plan guardrail. Mirrors packages/core/src/coach/guardrail.ts; messages are contract text. */

/** Max week-over-week TSS growth between loading weeks (cold-start ramp rule). */
const val MAX_WEEKLY_RAMP = 1.35

/** Minimum rest (or recovery-only) days per week. */
const val MIN_REST_DAYS = 1

/** Max share of weekly duration in the single longest session. */
const val LONG_RUN_MAX_FRACTION = 0.45

private const val MIN_SESSION_MIN = 10.0
private const val MAX_REPAIR_ITERS = 40
private const val PROJECTION_EPOCH = "2000-01-01"

private const val RULE_RAMP = "ramp"
private const val RULE_B2B = "back_to_back_hard"
private const val RULE_REST = "rest_minimum"
private const val RULE_LONG = "long_run_cap"
private const val ERROR = "error"

/**
 * Optional athlete context. A non-null [seedCtl] switches the ramp rule to the CTL-rise cap (ctlRampCap of
 * [experienceLevel], default intermediate) projected through the PMC; null uses the TSS ratio.
 * Mirrors packages/core/src/coach/guardrail.ts#PlanGuardrailContext.
 */
@Serializable
data class PlanGuardrailContext(
    val seedCtl: Double? = null,
    val seedAtl: Double? = null,
    val experienceLevel: ExperienceLevel? = null
)

/** A repaired plan plus the fixes applied. Mirrors the return type of guardrail.ts#repairPlan. */
@Serializable
data class RepairResult(val plan: TrainingPlan, val fixed: List<GuardrailViolation>)

private fun isHardDay(day: PlanDay) = day.sessions.any { it.type in QUALITY_TYPES }

/** Rest-only (an empty day counts as rest); a recovery RUN is not rest. Mirrors guardrail.ts#isRestDay. */
private fun isRestDay(day: PlanDay) = day.sessions.all { it.type == WorkoutType.REST }

/** The long-run cap ignores race-day sessions. Mirrors guardrail.ts#isCappedSession. */
private fun isCappedSession(s: WorkoutSuggestion) = s.type != WorkoutType.RACE

private fun absDay(weekNumber: Int, day: Int) = (weekNumber - 1) * 7 + (day - 1)

/** Day 1 of [week] when it and day 7 of [prev] are consecutive and both hard. Mirrors guardrail.ts#hardAcrossWeeks. */
private fun hardAcrossWeeks(prev: PlanWeek, week: PlanWeek): PlanDay? {
    val prevLast = prev.days.firstOrNull { it.day == 7 } ?: return null
    val first = week.days.firstOrNull { it.day == 1 } ?: return null
    if (absDay(week.weekNumber, 1) != absDay(prev.weekNumber, 7) + 1) return null
    return if (isHardDay(prevLast) && isHardDay(first)) first else null
}

private fun dayTss(day: PlanDay) = day.sessions.sumOf { it.targetTss ?: 0.0 }

private fun weekTss(week: PlanWeek) = week.targetTss ?: week.days.sumOf { dayTss(it) }

private fun weekDurationSec(days: List<PlanDay>) =
    days.sumOf { d -> d.sessions.filter(::isCappedSession).sumOf { it.targetDurationSec ?: 0.0 } }

private fun isLoadingWeek(week: PlanWeek) = week.phase != PlanPhase.RECOVERY && week.phase != PlanPhase.TAPER

private fun hasTargetTss(weeks: List<PlanWeek>) = weeks.any { w -> w.days.any { dayTss(it) > 0 } }

private data class WeekRise(val endCtl: Double, val rise: Double)

/** Projects session TSS onto consecutive days and measures each week's CTL rise. Mirrors guardrail.ts#projectWeekRises. */
private fun projectWeekRises(weeks: List<PlanWeek>, seedCtl: Double, seedAtl: Double): Map<Int, WeekRise> {
    val dailies = weeks.flatMap { week ->
        week.days.map { day ->
            DailyLoad(
                date = DateKeys.addDays(PROJECTION_EPOCH, (week.weekNumber - 1) * 7 + (day.day - 1)),
                tss = dayTss(day),
                durationSec = 0.0,
                distanceM = 0.0,
                method = LoadMethod.NONE
            )
        }
    }.sortedBy { it.date }
    if (dailies.isEmpty()) return emptyMap()
    val maxWeek = weeks.maxOf { it.weekNumber }
    val through = DateKeys.addDays(PROJECTION_EPOCH, maxWeek * 7 - 1)
    val ctlByDate = buildPmcSeries(dailies, PmcOptions(seedCtl = seedCtl, seedAtl = seedAtl, throughDate = through))
        .associate { it.date to it.ctl }
    var prevEnd = seedCtl
    val rises = LinkedHashMap<Int, WeekRise>()
    for (week in weeks.sortedBy { it.weekNumber }) {
        val endCtl = ctlByDate[DateKeys.addDays(PROJECTION_EPOCH, (week.weekNumber - 1) * 7 + 6)] ?: prevEnd
        rises[week.weekNumber] = WeekRise(endCtl, endCtl - prevEnd)
        prevEnd = endCtl
    }
    return rises
}

private fun violation(rule: String, weekNumber: Int, message: String) = GuardrailViolation(rule, message, ERROR, weekNumber)

/**
 * Validates a plan: ramp (CTL rise <= ctlRampCap with a seeded [ctx], else week TSS <= 1.35 x the previous
 * LOADING week; recovery/taper weeks are skipped), no hard sessions on consecutive day numbers, >= 1 rest day
 * per week, and longest session <= 45% of the week's duration. Week TSS prefers the cached week.targetTss.
 * Mirrors packages/core/src/coach/guardrail.ts#validatePlan.
 */
fun validatePlan(plan: TrainingPlan, ctx: PlanGuardrailContext = PlanGuardrailContext()): PlanValidation {
    val violations = mutableListOf<GuardrailViolation>()
    val experience = ctx.experienceLevel ?: ExperienceLevel.INTERMEDIATE
    val rampCap = ctlRampCap(experience)
    val rises = if (ctx.seedCtl != null && hasTargetTss(plan.weeks)) projectWeekRises(plan.weeks, ctx.seedCtl, ctx.seedAtl ?: 0.0) else null

    var prevLoadTss: Double? = null
    val sorted = plan.weeks.sortedBy { it.weekNumber }
    for ((wi, week) in sorted.withIndex()) {
        if (isLoadingWeek(week)) {
            val tss = weekTss(week)
            violations += if (rises != null) {
                ctlRampViolation(week.weekNumber, rises[week.weekNumber]?.rise ?: 0.0, rampCap, experience)
            } else {
                tssRampViolation(week.weekNumber, tss, prevLoadTss)
            }
            prevLoadTss = tss
        }
        val prevWeek = sorted.getOrNull(wi - 1)
        if (prevWeek != null && hardAcrossWeeks(prevWeek, week) != null) {
            val a = prevWeek.weekNumber
            val b = week.weekNumber
            violations += violation(
                RULE_B2B,
                b,
                "Weeks $a and $b have hard sessions on consecutive days (week $a day 7 & week $b day 1); allow ~48h between quality days."
            )
        }
        violations += backToBackViolations(week)
        if (week.days.count(::isRestDay) < MIN_REST_DAYS) {
            violations += violation(RULE_REST, week.weekNumber, "Week ${week.weekNumber} has no rest day; include at least $MIN_REST_DAYS.")
        }
        violations += longRunViolation(week)
    }
    return PlanValidation(valid = violations.none { it.severity == ERROR }, violations = violations, repaired = false)
}

private fun ctlRampViolation(n: Int, rise: Double, rampCap: Double, experience: ExperienceLevel): List<GuardrailViolation> {
    if (rise <= rampCap) return emptyList()
    val message = "Week $n adds ${toFixedString(rise, 1)} CTL points (cap ${jsString(rampCap)}/week for ${experience.wire} athletes)."
    return listOf(violation(RULE_RAMP, n, message))
}

private fun tssRampViolation(n: Int, tss: Double, prev: Double?): List<GuardrailViolation> {
    if (prev == null || prev <= 0 || tss <= prev * MAX_WEEKLY_RAMP) return emptyList()
    val pct = jsString(jsRound((tss / prev - 1) * 100))
    val cap = jsString(jsRound((MAX_WEEKLY_RAMP - 1) * 100))
    return listOf(violation(RULE_RAMP, n, "Week $n load jumps $pct% over the previous loading week (cap is $cap%)."))
}

private fun backToBackViolations(week: PlanWeek): List<GuardrailViolation> {
    val n = week.weekNumber
    return week.days.sortedBy { it.day }.zipWithNext()
        .filter { (a, b) -> b.day == a.day + 1 && isHardDay(a) && isHardDay(b) }
        .map { (a, b) ->
            violation(RULE_B2B, n, "Week $n has hard sessions on consecutive days (${a.day} & ${b.day}); allow ~48h between quality days.")
        }
}

private fun longRunViolation(week: PlanWeek): List<GuardrailViolation> {
    val totalSec = weekDurationSec(week.days)
    val longestSec = max(
        0.0,
        week.days.flatMap { d -> d.sessions.filter(::isCappedSession).map { it.targetDurationSec ?: 0.0 } }.maxOrNull() ?: 0.0
    )
    if (totalSec <= 0 || longestSec / totalSec <= LONG_RUN_MAX_FRACTION) return emptyList()
    val share = jsString(jsRound(longestSec / totalSec * 100))
    val cap = jsString(jsRound(LONG_RUN_MAX_FRACTION * 100))
    return listOf(violation(RULE_LONG, week.weekNumber, "Week ${week.weekNumber}'s longest run is $share% of weekly volume (cap $cap%)."))
}

/** Recomputes a week's cached targetTss (0 dp) and targetDistanceKm (1 dp). Mirrors guardrail.ts#withWeekTotals. */
private fun withWeekTotals(week: PlanWeek): PlanWeek {
    val tss = week.days.sumOf { dayTss(it) }
    val distanceM = week.days.sumOf { d -> d.sessions.sumOf { it.targetDistanceM ?: 0.0 } }
    return week.copy(targetTss = toFixed(tss, 0), targetDistanceKm = toFixed(distanceM / 1000, 1))
}

/** Caps the longest session at <= 45% of the week. Mirrors guardrail.ts#capLongRun. */
private fun capLongRun(days: List<PlanDay>, weekNumber: Int, fixed: MutableList<GuardrailViolation>): List<PlanDay> {
    val totalSec = weekDurationSec(days)
    if (totalSec <= 0) return days
    var maxSec = 0.0
    var di = -1
    var si = -1
    days.forEachIndexed { dIdx, day ->
        day.sessions.forEachIndexed { sIdx, s ->
            val dur = if (isCappedSession(s)) s.targetDurationSec ?: 0.0 else 0.0
            if (dur > maxSec) {
                maxSec = dur
                di = dIdx
                si = sIdx
            }
        }
    }
    if (maxSec <= 0 || maxSec / totalSec <= LONG_RUN_MAX_FRACTION) return days
    val newSec = LONG_RUN_MAX_FRACTION / (1 - LONG_RUN_MAX_FRACTION) * (totalSec - maxSec)
    val newMin = max(MIN_SESSION_MIN, floor(newSec / 60))
    val out = days.toMutableList()
    val sessions = out[di].sessions.toMutableList()
    sessions[si] = rescaleSession(sessions[si], newMin)
    out[di] = out[di].copy(sessions = sessions)
    val cap = jsString(jsRound(LONG_RUN_MAX_FRACTION * 100))
    fixed +=
        violation(
            RULE_LONG,
            weekNumber,
            "Repaired: capped week $weekNumber's long run at ${jsString(newMin)} min (≤$cap% of weekly volume)."
        )
    return out
}

/** Scales the week's long + quality sessions by [factor] (never below 10 min); null when nothing changed. */
private fun scaleWeekQualityLong(week: PlanWeek, factor: Double): PlanWeek? {
    var changed = false
    val days = week.days.map { d ->
        d.copy(
            sessions = d.sessions.map { s ->
                val scalable = s.type == WorkoutType.LONG || s.type in QUALITY_TYPES
                val durSec = s.targetDurationSec ?: 0.0
                val newMin = durSec * factor / 60
                if (scalable && durSec > 0 && newMin >= MIN_SESSION_MIN) {
                    changed = true
                    rescaleSession(s, newMin)
                } else {
                    s
                }
            }
        )
    }
    return if (changed) week.copy(days = days) else null
}

/**
 * Scales weeks[idx] (in place) by [factor] while [overCap] holds, at most MAX_REPAIR_ITERS times or until no
 * session can shrink further. Returns whether anything was scaled.
 */
private fun scaleWhile(weeks: MutableList<PlanWeek>, idx: Int, factor: Double, overCap: () -> Boolean): Boolean {
    var steps = 0
    while (steps < MAX_REPAIR_ITERS && overCap()) {
        val next = scaleWeekQualityLong(weeks[idx], factor) ?: break
        weeks[idx] = withWeekTotals(next)
        steps++
    }
    return steps > 0
}

/** The hard day as an easy run of the same length, paced off the athlete's recovered threshold. Mirrors guardrail.ts#downgradeToEasy. */
private fun downgradeToEasy(day: PlanDay): PlanDay {
    val hard = day.sessions.firstOrNull()
    val dur = (hard?.targetDurationSec ?: 2700.0) / 60
    val threshold = hard?.let { thresholdFromSession(it) } ?: DEFAULT_THRESHOLD_MPS
    val easy = makeSession(
        WorkoutType.EASY,
        dur,
        threshold,
        date = day.date,
        rationale = "Downgraded to easy to keep ~48h between quality sessions."
    )
    return day.copy(sessions = listOf(easy))
}

/** Repairs (1) back-to-back hard days and (2) the rest minimum in one week; returns the new day list. */
private fun repairStructure(week: PlanWeek, fixed: MutableList<GuardrailViolation>): List<PlanDay> {
    val n = week.weekNumber
    val days = week.days.sortedBy { it.day }.toMutableList()
    for (i in 1 until days.size) {
        if (days[i].day == days[i - 1].day + 1 && isHardDay(days[i]) && isHardDay(days[i - 1])) {
            days[i] = downgradeToEasy(days[i])
            fixed += violation(RULE_B2B, n, "Repaired: converted day ${days[i].day} to an easy run.")
        }
    }
    val restCount = days.count(::isRestDay)
    if (restCount < MIN_REST_DAYS) {
        val candidates = days.indices
            .filter { !isRestDay(days[it]) && !isHardDay(days[it]) }
            .sortedBy { dayTss(days[it]) }
        for (idx in candidates.take(MIN_REST_DAYS - restCount)) {
            val day = days[idx]
            val rest = makeSession(
                WorkoutType.REST,
                0.0,
                DEFAULT_THRESHOLD_MPS,
                date = day.date,
                rationale = "Inserted a rest day to meet the weekly recovery minimum."
            )
            days[idx] = day.copy(sessions = listOf(rest))
            fixed += violation(RULE_REST, n, "Repaired: converted day ${day.day} to a rest day.")
        }
    }
    return days
}

/**
 * Repairs error-level violations without mutating [plan]: (1) the later of two consecutive hard days becomes an
 * easy run of the same length; (2) the lowest-TSS non-hard day becomes rest when below the minimum; (3) the
 * longest session is capped at 45%; (4) each loading week's long + quality sessions are scaled (x0.85 per step
 * with a seeded ctx, x0.9 otherwise; up to 40 steps) until its ramp is within cap. Week totals are recomputed.
 * Re-validate afterwards: some violations may remain. Mirrors packages/core/src/coach/guardrail.ts#repairPlan.
 */
fun repairPlan(plan: TrainingPlan, ctx: PlanGuardrailContext = PlanGuardrailContext()): RepairResult {
    val fixed = mutableListOf<GuardrailViolation>()
    val sorted = plan.weeks.sortedBy { it.weekNumber }.toMutableList()
    // (0) Back-to-back hard across a week boundary: day 1 of the later week becomes easy.
    for (wi in 1 until sorted.size) {
        val first = hardAcrossWeeks(sorted[wi - 1], sorted[wi]) ?: continue
        val week = sorted[wi]
        sorted[wi] = week.copy(days = week.days.map { if (it === first) downgradeToEasy(it) else it })
        fixed += violation(RULE_B2B, week.weekNumber, "Repaired: converted week ${week.weekNumber} day 1 to an easy run.")
    }
    val weeks = sorted.map { week ->
        val days = capLongRun(repairStructure(week, fixed), week.weekNumber, fixed)
        withWeekTotals(week.copy(days = days))
    }.toMutableList()

    val useCtlRamp = ctx.seedCtl != null && hasTargetTss(weeks)
    val experience = ctx.experienceLevel ?: ExperienceLevel.INTERMEDIATE
    val rampCap = ctlRampCap(experience)
    val loadingOrder = weeks.filter(::isLoadingWeek).sortedBy { it.weekNumber }.map { it.weekNumber }

    var prevLoadTss: Double? = null
    for (weekNumber in loadingOrder) {
        val idx = weeks.indexOfFirst { it.weekNumber == weekNumber }
        val prev = prevLoadTss
        val scaled = when {
            useCtlRamp -> scaleWhile(weeks, idx, 0.85) {
                val rise = projectWeekRises(weeks, ctx.seedCtl, ctx.seedAtl ?: 0.0)[weekNumber]?.rise
                rise != null && rise > rampCap
            }

            prev != null && prev > 0 -> scaleWhile(weeks, idx, 0.9) { weekTss(weeks[idx]) > prev * MAX_WEEKLY_RAMP }

            else -> false
        }
        if (scaled) {
            val detail = if (useCtlRamp) {
                "CTL rise ≤ ${jsString(rampCap)} pts/week"
            } else {
                "≤ ${jsString(jsRound((MAX_WEEKLY_RAMP - 1) * 100))}% week-over-week"
            }
            fixed += violation(RULE_RAMP, weekNumber, "Repaired: scaled week $weekNumber down to keep the ramp within cap ($detail).")
        }
        prevLoadTss = weekTss(weeks[idx])
    }
    return RepairResult(plan = plan.copy(weeks = weeks), fixed = fixed)
}
