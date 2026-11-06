package org.stride.core.science

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.stride.core.science.model.Activity
import org.stride.core.science.model.ActivitySource
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.SportType
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.plan.FitnessSeed
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.SEED_MIN_WEEKLY_MIN
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.fitnessSeed
import org.stride.core.science.plan.validatePlan
import org.stride.core.science.util.DateKeys

/** MVP-025: plans seeded from recent training stay inside the guardrails (the TS twin is packages/core/test/seed.test.ts). */
class SeededPlanTest {
    private val start = "2026-07-13"

    private fun plan(seed: FitnessSeed?, weeks: Int, goal: RaceGoal = RaceGoal(distance = RaceDistance.TEN_K)): TrainingPlan {
        val profile = AthleteProfile(thresholdSpeedMps = 3.6)
        return buildPlanSkeleton(PlanSkeletonParams(profile, goal, weeks, start, "p", "${start}T00:00:00Z", seed))
    }

    private fun weekMinutes(plan: TrainingPlan, i: Int): Double = plan.weeks[i].days.sumOf { d ->
        d.sessions.filter { it.type != WorkoutType.RACE }.sumOf { (it.targetDurationSec ?: 0.0) / 60 }
    }

    private val seeds = listOf(
        FitnessSeed(30.0, 15.0, null, 3),
        FitnessSeed(75.0, 30.0, null, 9),
        FitnessSeed(180.0, 60.0, 45.0, 12),
        FitnessSeed(240.0, 35.0, 48.0, 12),
        FitnessSeed(420.0, 120.0, 55.0, 16),
        FitnessSeed(700.0, 150.0, 62.0, 20),
        FitnessSeed(120.0, 110.0, 40.0, 8)
    )

    @Test
    fun seededPlansPassTheGuardrailAndStartFromRecentVolume() {
        for (seed in seeds) {
            for (weeks in 4..24) {
                for (distance in RaceDistance.entries.filter { it != RaceDistance.CUSTOM }) {
                    for (dated in listOf(false, true)) {
                        val goal = RaceGoal(distance = distance, date = if (dated) DateKeys.addDays(start, weeks * 7 - 1) else null)
                        val p = plan(seed, weeks, goal)
                        val where = "$seed ${weeks}w $distance dated=$dated"
                        assertEquals(emptyList(), validatePlan(p).violations, where)
                        val first = max(SEED_MIN_WEEKLY_MIN, seed.weeklyMinutes)
                        assertTrue(abs(weekMinutes(p, 0) / first - 1) <= 0.1, where)
                        var prev: Double? = null
                        p.weeks.forEachIndexed { i, w ->
                            if (w.phase == PlanPhase.RECOVERY || w.phase == PlanPhase.TAPER) return@forEachIndexed
                            val m = weekMinutes(p, i)
                            prev?.let { assertTrue(m / it <= 1.1, "$where week ${i + 1}") }
                            prev = m
                        }
                    }
                }
            }
        }
    }

    @Test
    fun noSeedIsTheFixedSkeleton() {
        val unseeded = PlanSkeletonParams(
            AthleteProfile(thresholdSpeedMps = 3.6),
            RaceGoal(distance = RaceDistance.TEN_K),
            12,
            start,
            "p",
            "${start}T00:00:00Z"
        )
        assertEquals(plan(null, 12), buildPlanSkeleton(unseeded))
        assertEquals(45.0 + 50 + 40 + 40 + 68, weekMinutes(plan(null, 12), 0))
    }

    @Test
    fun fitnessSeedNeedsThreeRunsInFourWeeks() {
        fun run(id: String, day: Int) = Activity(
            id = id,
            source = ActivitySource.UPLOAD,
            sportType = SportType.RUN,
            name = id,
            startDate = "${DateKeys.addDays("2026-07-09", day)}T06:00:00Z",
            distance = 8000.0,
            movingTime = 2400.0,
            elapsedTime = 2400.0
        )
        assertNull(fitnessSeed(listOf(run("a", 0), run("b", -27), run("c", -28)), "2026-07-09"))
        val seed = fitnessSeed(listOf(run("a", 0), run("b", -27), run("c", -14)), "2026-07-09")
        assertEquals(FitnessSeed(weeklyMinutes = 30.0, longestRunMin = 40.0, vdot = seed?.vdot, runCount = 3), seed)
        assertTrue((seed?.vdot ?: 0.0) > 0)
    }
}
