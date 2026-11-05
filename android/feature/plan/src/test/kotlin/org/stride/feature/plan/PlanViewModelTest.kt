package org.stride.feature.plan

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.plan.PlanRequest
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.model.RaceGoal
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.plan.validatePlan
import org.stride.core.testing.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun ReceiveTurbine<PlanUiState>.awaitWhere(predicate: (PlanUiState) -> Boolean): PlanUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    private fun DemoDataFixture.viewModel() = PlanViewModel(plans, prefs, clock)

    @Test
    fun createShowsAPlanThatPassedTheGuardrailsThenDeleteReturnsToTheForm() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val vm = fx.viewModel()
        vm.uiState.test {
            val form = awaitWhere { !it.isLoading }
            assertNull(form.plan)
            assertEquals(LocalDate.parse("2026-11-02"), form.earliestRaceDate)

            vm.setDistance(RaceDistance.HALF)
            vm.setRaceDate(LocalDate.parse("2027-02-28"))
            vm.setGoalTime("1:50:00")
            vm.create()
            val shown = awaitWhere { it.plan != null }
            val plan = assertNotNull(shown.plan)
            assertTrue(validatePlan(plan, fx.plans.guardrailContext()).valid, "every displayed plan passed validatePlan")
            assertEquals(RaceDistance.HALF, plan.goal.distance)
            assertEquals(6_600.0, plan.goal.goalTimeSec)
            assertEquals("2026-11-02", plan.startDate)
            assertEquals(17, plan.weeks.size, "2 Nov through the race week ending 28 Feb")
            assertTrue(shown.violations.isEmpty())

            vm.delete()
            assertNull(awaitWhere { it.plan == null }.plan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun todayInsideThePlanIsTheCurrentWeek() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        fx.plans.create(PlanRequest(RaceDistance.TEN_K, LocalDate.parse("2027-01-31")))
        fx.clock.instant = java.time.Instant.parse("2026-11-18T07:00:00Z") // Wednesday of week 3
        fx.viewModel().uiState.test {
            val state = awaitWhere { it.plan != null }
            assertEquals(2, state.currentWeekIndex)
            assertEquals("2026-11-18", state.today)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun badGoalTimeIsAnErrorAndSavesNothing() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        val vm = fx.viewModel()
        vm.uiState.test {
            awaitWhere { !it.isLoading }
            vm.setGoalTime("fast")
            vm.create()
            val state = awaitWhere { it.error != null }
            assertNull(state.plan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun formIsPrefilledFromTheProfileRaceGoal() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.prefs.updateProfile(fx.prefs.current().profile.copy(raceGoal = RaceGoal(42_195.0, "2027-04-18", 12_600.0)))
        fx.viewModel().uiState.test {
            val state = awaitWhere { it.form.distance == RaceDistance.MARATHON }
            assertEquals(LocalDate.parse("2027-04-18"), state.form.raceDate)
            assertEquals("3:30:00", state.form.goalTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun goalTimeParsing() {
        assertEquals(6_300.0, parseGoalTime("1:45:00"))
        assertEquals(2_730.0, parseGoalTime("45:30"))
        assertEquals(2_700.0, parseGoalTime("45"))
        assertNull(parseGoalTime("1:75:00"))
        assertNull(parseGoalTime("abc"))
        assertNull(parseGoalTime("0:00"))
        assertEquals("1:45:00", formatGoalTime(6_300.0))
        assertEquals(RaceDistance.HALF, raceDistanceFor(21_097.0))
        assertNull(raceDistanceFor(15_000.0))
    }
}
