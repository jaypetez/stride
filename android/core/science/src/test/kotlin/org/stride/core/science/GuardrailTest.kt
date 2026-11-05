package org.stride.core.science

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.stride.core.science.anchors.EstimatedAnchors
import org.stride.core.science.anchors.applyEstimatedAnchors
import org.stride.core.science.coach.QUALITY_TYPES
import org.stride.core.science.coach.RedFlagInput
import org.stride.core.science.coach.detectRedFlags
import org.stride.core.science.coach.makeSession
import org.stride.core.science.coach.proposeNextWorkout
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.AcwrPoint
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.CoachContext
import org.stride.core.science.model.ExperienceLevel
import org.stride.core.science.model.LoadMethod
import org.stride.core.science.model.PlanDay
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.PlanWeek
import org.stride.core.science.model.PmcPoint
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.RecentActivity
import org.stride.core.science.model.RedFlagSeverity
import org.stride.core.science.model.SportType
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.model.ZoneDistribution
import org.stride.core.science.plan.PlanGuardrailContext
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.repairPlan
import org.stride.core.science.plan.validatePlan
import org.stride.core.science.util.DateKeys

/** Guardrail tests for MVP-017 (next workout) and MVP-018 (plans), plus anchor precedence (MVP-011). */
class GuardrailTest {
    private val profile = AthleteProfile(id = "t", thresholdSpeedMps = 3.33, lthr = 165.0, maxHr = 188.0, medicalClearance = true)

    private fun context(
        tsb: Double = 0.0,
        hardPct: Double = 0.0,
        flag: AcwrFlag = AcwrFlag.OK,
        recent: List<RecentActivity> = emptyList(),
        phase: PlanPhase? = null
    ) = CoachContext(
        generatedAt = "2026-07-09T00:00:00Z",
        profile = profile,
        fitness = PmcPoint("2026-07-08", ctl = 40.0, atl = 40.0 - tsb, tsb = tsb),
        acwr = AcwrPoint("2026-07-08", acwr = 1.0, acuteLoad = 40.0, chronicLoad = 40.0, flag = flag),
        weeklyDistribution = ZoneDistribution(0.0, 0.0, 0.0, 100 - hardPct, 0.0, hardPct),
        recentActivities = recent,
        planPhase = phase
    )

    private fun run(tss: Double, durationSec: Double, km: Double) =
        RecentActivity("2026-07-08", "Run", SportType.RUN, km, durationSec, tss, LoadMethod.RTSS)

    // --- MVP-017: next workout ---

    @Test
    fun neverTwoHardDaysWithin48h() {
        val hardYesterday = run(tss = 90.0, durationSec = 3600.0, km = 12.0) // implied IF ~0.95
        for (tsb in listOf(-20.0, -10.0, 0.0, 10.0, 25.0)) {
            for (hardPct in listOf(0.0, 5.0, 17.0, 30.0)) {
                for (phase in listOf(null, PlanPhase.BUILD, PlanPhase.PEAK)) {
                    val next = proposeNextWorkout(context(tsb, hardPct, recent = listOf(hardYesterday), phase = phase), profile)
                    assertFalse(next.type in QUALITY_TYPES, "quality session after a hard day (tsb=$tsb, hard=$hardPct, phase=$phase)")
                }
            }
        }
    }

    @Test
    fun backsOffWhenTsbBelowMinus30() {
        for (tsb in listOf(-30.5, -31.0, -45.0, -80.0)) {
            for (hardPct in listOf(0.0, 50.0)) {
                val next = proposeNextWorkout(context(tsb, hardPct, phase = PlanPhase.PEAK), profile)
                assertEquals(WorkoutType.RECOVERY, next.type, "tsb=$tsb")
                assertTrue(next.rationale.contains("back off"))
            }
            val flags = detectRedFlags(RedFlagInput(tsb = tsb, profile = profile))
            assertTrue(flags.any { it.severity == RedFlagSeverity.WARNING && it.source == "load" }, "tsb=$tsb should warn")
        }
        assertEquals(WorkoutType.RECOVERY, proposeNextWorkout(context(tsb = 5.0, flag = AcwrFlag.VERY_HIGH), profile).type)
    }

    @Test
    fun freshAthleteWithLittleIntensityGetsQuality() {
        assertEquals(WorkoutType.THRESHOLD, proposeNextWorkout(context(tsb = 0.0, hardPct = 5.0), profile).type)
        assertEquals(WorkoutType.INTERVAL, proposeNextWorkout(context(tsb = 0.0, hardPct = 5.0, phase = PlanPhase.PEAK), profile).type)
    }

    // --- MVP-018: plans ---

    private fun skeleton(weeks: Int, distance: RaceDistance, p: AthleteProfile) = buildPlanSkeleton(
        PlanSkeletonParams(p, RaceGoal(distance = distance), weeks, "2026-07-13", "plan-$weeks", "2026-07-09T00:00:00Z")
    )

    @Test
    fun everyGeneratedPlanPassesValidatePlan() {
        val profiles = listOf(
            profile,
            AthleteProfile(id = "vdot", vdot = 38.0),
            AthleteProfile(id = "none"),
            AthleteProfile(id = "fast", thresholdSpeedMps = 5.0, experienceLevel = ExperienceLevel.ADVANCED)
        )
        for (p in profiles) {
            for (distance in RaceDistance.entries) {
                for (weeks in 1..24) {
                    val plan = skeleton(weeks, distance, p)
                    assertEquals(weeks, plan.weeks.size)
                    val result = validatePlan(plan)
                    assertTrue(result.valid, "${p.id} $distance $weeks weeks: ${result.violations}")
                    assertNoHardDaysWithin48h(plan)
                }
            }
        }
    }

    private fun assertNoHardDaysWithin48h(plan: TrainingPlan) {
        val hardDates = plan.weeks.flatMap { w -> w.days.filter { d -> d.sessions.any { it.type in QUALITY_TYPES } } }
            .map { it.date!! }
            .sorted()
        for (i in 1 until hardDates.size) {
            assertTrue(
                DateKeys.daysBetween(hardDates[i - 1], hardDates[i]) >= 2,
                "hard days ${hardDates[i - 1]} and ${hardDates[i]} in ${plan.id}"
            )
        }
    }

    private fun week(n: Int, phase: PlanPhase, vararg sessions: Pair<Int, WorkoutType>, minutes: Double = 45.0) = PlanWeek(
        weekNumber = n,
        phase = phase,
        focus = "test",
        days = sessions.map { (day, type) ->
            PlanDay(
                day,
                sessions = listOf(
                    makeSession(
                        type,
                        if (type ==
                            WorkoutType.REST
                        ) {
                            0.0
                        } else {
                            minutes
                        },
                        3.33
                    )
                )
            )
        }
    )

    private fun plan(vararg weeks: PlanWeek) = TrainingPlan("p", "2026-07-09T00:00:00Z", RaceGoal(), "2026-07-13", weeks = weeks.toList())

    @Test
    fun repairFixesBackToBackHardAndMissingRest() {
        val bad = plan(
            week(
                1,
                PlanPhase.BUILD,
                1 to WorkoutType.EASY,
                2 to WorkoutType.THRESHOLD,
                3 to WorkoutType.INTERVAL,
                4 to WorkoutType.TEMPO,
                5 to WorkoutType.EASY,
                6 to WorkoutType.EASY,
                7 to WorkoutType.EASY
            )
        )
        val before = validatePlan(bad)
        assertFalse(before.valid)
        assertEquals(setOf("back_to_back_hard", "rest_minimum"), before.violations.map { it.rule }.toSet())

        val repaired = repairPlan(bad)
        assertTrue(repaired.fixed.isNotEmpty())
        assertTrue(validatePlan(repaired.plan).valid, "${validatePlan(repaired.plan).violations}")
        assertNotEquals(bad, repaired.plan)
        // The input is never mutated.
        assertEquals(before, validatePlan(bad))
    }

    @Test
    fun repairCapsAnOversizedLongRunAndRampWithSeededContext() {
        val base = week(1, PlanPhase.BASE, 1 to WorkoutType.REST, 2 to WorkoutType.EASY, 4 to WorkoutType.EASY, 7 to WorkoutType.LONG)
        val spike = week(
            2,
            PlanPhase.BUILD,
            1 to WorkoutType.REST,
            2 to WorkoutType.THRESHOLD,
            4 to WorkoutType.TEMPO,
            7 to WorkoutType.LONG,
            minutes = 120.0
        )
        val bad = plan(base, spike)
        val ctx = PlanGuardrailContext(seedCtl = 20.0, seedAtl = 20.0, experienceLevel = ExperienceLevel.BEGINNER)
        assertFalse(validatePlan(bad, ctx).valid)
        val repaired = repairPlan(bad, ctx)
        val after = validatePlan(repaired.plan, ctx)
        assertTrue(after.violations.none { it.rule == "back_to_back_hard" || it.rule == "rest_minimum" })
        assertTrue(repaired.fixed.any { it.rule == "ramp" })
    }

    // --- MVP-011: user overrides beat estimated anchors ---

    @Test
    fun userAnchorsTakePrecedenceOverEstimates() {
        val estimated = EstimatedAnchors(vdot = 52.0, thresholdSpeedMps = 3.9, maxHr = 201.0, lthr = 181.0)
        val user = AthleteProfile(thresholdSpeedMps = 3.2, maxHr = 186.0, restingHr = 48.0)
        val merged = applyEstimatedAnchors(user, estimated, nowIso = "2026-07-09T00:00:00Z")
        assertEquals(3.2, merged.thresholdSpeedMps)
        assertEquals(186.0, merged.maxHr)
        assertEquals(48.0, merged.restingHr)
        assertEquals(52.0, merged.vdot) // missing -> filled
        assertEquals(181.0, merged.lthr) // missing -> filled
        assertEquals("2026-07-09T00:00:00Z", merged.anchorsUpdatedAt)
        assertEquals(AthleteProfile(), applyEstimatedAnchors(AthleteProfile(), EstimatedAnchors()))
    }
}
