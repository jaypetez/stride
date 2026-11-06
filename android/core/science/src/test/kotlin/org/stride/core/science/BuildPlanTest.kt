package org.stride.core.science

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.PlanKind
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.plan.BUILD_MAX_WEEKS
import org.stride.core.science.plan.BUILD_MIN_WEEKS
import org.stride.core.science.plan.FitnessSeed
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.SEED_MIN_WEEKLY_MIN
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.clampBuildWeeks
import org.stride.core.science.plan.validatePlan

/** MVP-026: no-race build plans stay inside the guardrails (the TS twin is packages/core/test/build-plan.test.ts). */
class BuildPlanTest {
    private val start = "2026-07-13"

    private fun plan(seed: FitnessSeed?, weeks: Int, goal: RaceGoal = RaceGoal(distance = RaceDistance.TEN_K)): TrainingPlan {
        val profile = AthleteProfile(thresholdSpeedMps = 3.6)
        return buildPlanSkeleton(
            PlanSkeletonParams(profile, goal, weeks, start, "b", "${start}T00:00:00Z", seed, PlanKind.BUILD)
        )
    }

    private fun weekMinutes(plan: TrainingPlan, i: Int): Double = plan.weeks[i].days.sumOf { d ->
        d.sessions.sumOf { (it.targetDurationSec ?: 0.0) / 60 }
    }

    private val seeds = listOf(
        null,
        FitnessSeed(30.0, 15.0, null, 3),
        FitnessSeed(75.0, 30.0, null, 9),
        FitnessSeed(180.0, 60.0, 45.0, 12),
        FitnessSeed(240.0, 35.0, 48.0, 12),
        FitnessSeed(420.0, 120.0, 55.0, 16),
        FitnessSeed(700.0, 150.0, 62.0, 20),
        FitnessSeed(120.0, 110.0, 40.0, 8)
    )

    @Test
    fun buildPlansPassTheGuardrailWithNoPeakTaperOrRace() {
        for (seed in seeds) {
            for (weeks in BUILD_MIN_WEEKS..BUILD_MAX_WEEKS) {
                val p = plan(seed, weeks)
                val where = "$seed ${weeks}w"
                assertEquals(emptyList(), validatePlan(p).violations, where)
                assertEquals(weeks, p.weeks.size, where)
                assertEquals(PlanKind.BUILD, p.kind, where)
                p.weeks.forEach { w ->
                    assertTrue(w.phase in setOf(PlanPhase.BASE, PlanPhase.BUILD, PlanPhase.RECOVERY), where)
                    assertEquals(w.weekNumber % 4 == 0, w.phase == PlanPhase.RECOVERY, "$where week ${w.weekNumber}")
                    assertTrue(w.days.flatMap { it.sessions }.none { it.type == WorkoutType.RACE }, where)
                }
                if (seed != null) {
                    val first = max(SEED_MIN_WEEKLY_MIN, seed.weeklyMinutes)
                    assertTrue(abs(weekMinutes(p, 0) / first - 1) <= 0.1, where)
                    var prev: Double? = null
                    p.weeks.forEachIndexed { i, w ->
                        if (w.phase == PlanPhase.RECOVERY) return@forEachIndexed
                        val m = weekMinutes(p, i)
                        prev?.let { assertTrue(m / it <= 1.1, "$where week ${i + 1}") }
                        prev = m
                    }
                }
            }
        }
    }

    @Test
    fun buildPlansIgnoreTheRaceDateAndClampTheirLength() {
        val dated = plan(null, 10, RaceGoal(distance = RaceDistance.TEN_K, date = "2026-08-01"))
        assertEquals(start, dated.startDate)
        assertEquals(10, dated.weeks.size)
        assertEquals(4, clampBuildWeeks(1))
        assertEquals(16, clampBuildWeeks(40))
        assertEquals(4, plan(null, 2).weeks.size)
        assertEquals(16, plan(null, 30).weeks.size)
    }

    @Test
    fun racePlansCarryNoKind() {
        val race = buildPlanSkeleton(
            PlanSkeletonParams(AthleteProfile(), RaceGoal(), 8, start, "r", start)
        )
        assertNull(race.kind)
        assertTrue(race.weeks.any { it.phase == PlanPhase.TAPER })
    }
}
