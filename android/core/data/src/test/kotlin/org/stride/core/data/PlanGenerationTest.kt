package org.stride.core.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.stride.core.data.plan.MAX_PLAN_WEEKS
import org.stride.core.data.plan.MIN_PLAN_WEEKS
import org.stride.core.data.plan.PlanRequest
import org.stride.core.data.plan.PlanResult
import org.stride.core.data.plan.generatePlan
import org.stride.core.data.plan.planStartDate
import org.stride.core.data.plan.planWeeks
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.plan.PlanGuardrailContext
import org.stride.core.science.plan.validatePlan

class PlanGenerationTest {
    private val today = LocalDate.parse("2026-11-01") // a Sunday

    @Test
    fun startIsTheNextMonday() {
        assertEquals(LocalDate.parse("2026-11-02"), planStartDate(today))
        assertEquals(LocalDate.parse("2026-11-09"), planStartDate(LocalDate.parse("2026-11-02")))
        assertEquals(DayOfWeek.MONDAY, planStartDate(LocalDate.parse("2026-11-05")).dayOfWeek)
    }

    @Test
    fun weeksRunThroughTheRaceWeekAndAreClamped() {
        val start = LocalDate.parse("2026-11-02")
        assertEquals(12, planWeeks(start, start.plusWeeks(11).plusDays(6)))
        assertEquals(12, planWeeks(start, start.plusWeeks(11)))
        assertEquals(MIN_PLAN_WEEKS, planWeeks(start, start.plusDays(3)))
        assertEquals(MAX_PLAN_WEEKS, planWeeks(start, start.plusWeeks(60)))
    }

    @Test
    fun everyCreatedPlanPassesTheGuardrails() {
        val contexts =
            listOf(
                PlanGuardrailContext(),
                PlanGuardrailContext(seedCtl = 45.0, seedAtl = 50.0),
                PlanGuardrailContext(seedCtl = 5.0, seedAtl = 5.0)
            )
        for (ctx in contexts) {
            for (distance in listOf(RaceDistance.FIVE_K, RaceDistance.TEN_K, RaceDistance.HALF, RaceDistance.MARATHON)) {
                for (weeksOut in listOf(2L, 8L, 16L, 30L)) {
                    val request = PlanRequest(distance, today.plusWeeks(weeksOut), goalTimeSec = null)
                    when (val result = generatePlan(request, AthleteProfile(), today, ctx, "p", "2026-11-01T07:00:00Z")) {
                        is PlanResult.Created -> {
                            assertTrue(validatePlan(result.plan, ctx).valid, "$distance $weeksOut $ctx")
                            assertEquals("2026-11-02", result.plan.startDate)
                            assertTrue(result.plan.weeks.size in MIN_PLAN_WEEKS..MAX_PLAN_WEEKS)
                        }

                        is PlanResult.Rejected -> assertTrue(result.violations.isNotEmpty())

                        is PlanResult.Invalid -> error("unexpected: ${result.message}")
                    }
                }
            }
        }
    }

    @Test
    fun aRaceBeforeThePlanStartIsInvalid() {
        val result = generatePlan(PlanRequest(RaceDistance.TEN_K, today), AthleteProfile(), today, PlanGuardrailContext(), "p", "t")
        assertIs<PlanResult.Invalid>(result)
    }

    @Test
    fun goalTimeIsCarriedOnThePlan() {
        val result = generatePlan(
            PlanRequest(RaceDistance.HALF, today.plusWeeks(12), goalTimeSec = 5400.0),
            AthleteProfile(),
            today,
            PlanGuardrailContext(),
            "p",
            "t"
        )
        val plan = assertIs<PlanResult.Created>(result).plan
        assertEquals(5400.0, plan.goal.goalTimeSec)
        assertEquals(RaceDistance.HALF, plan.goal.distance)
        assertEquals(12, plan.weeks.size)
    }
}
