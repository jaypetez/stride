package org.stride.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.plan.PlanRepository
import org.stride.core.data.plan.PlanRequest
import org.stride.core.data.plan.PlanResult
import org.stride.core.data.plan.StoredPlan
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.validatePlan

/** MVP-018: plans are generated from the demo athlete, saved as JSON and only ever returned when valid. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createSavesAValidPlanAndRecordsTheGoal() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher).load(env.db)
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tmp.root, "${PlanRepository.FILE_NAME}.preferences_pb")
        }
        val repo = PlanRepository(store, env.holder, env.prefs, env.clock, env.recomputer, dispatcher)
        assertNull(repo.plan.first())

        val ctx = repo.guardrailContext()
        assertNotNull(ctx.seedCtl, "demo history seeds the CTL ramp check")
        val race = LocalDate.parse("2027-02-28")
        val result = repo.create(PlanRequest(RaceDistance.HALF, race, goalTimeSec = 6000.0))
        val created = assertIs<PlanResult.Created>(result)

        val shown = assertNotNull(repo.plan.first())
        assertEquals(created.plan, shown)
        assertTrue(validatePlan(shown, ctx).valid)
        assertEquals(race.toString(), env.prefs.current().profile.raceGoal?.date)
        assertEquals(6000.0, env.prefs.current().profile.raceGoal?.targetTimeSec)

        repo.delete()
        assertNull(repo.plan.first())
    }

    /** MVP-025: the plan starts from the demo athlete's last 4 weeks, not fixed minutes. */
    @Test
    fun createSeedsThePlanFromRecentActivities() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "seed.preferences_pb") }
        val repo = PlanRepository(store, env.holder, env.prefs, env.clock, env.recomputer, dispatcher)
        assertNull(repo.fitnessSeed(), "no history, no seed")
        val unseeded = assertIs<PlanResult.Created>(repo.create(PlanRequest(RaceDistance.HALF, LocalDate.parse("2027-02-28"))))
        assertNull(unseeded.seed)

        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher).load(env.db)
        val seed = assertNotNull(repo.fitnessSeed(), "the demo history has recent runs")
        assertTrue(seed.runCount >= 3 && seed.weeklyMinutes > 0 && seed.longestRunMin > 0)
        val created = assertIs<PlanResult.Created>(repo.create(PlanRequest(RaceDistance.HALF, LocalDate.parse("2027-02-28"))))
        assertEquals(seed, created.seed)
        val expected = buildPlanSkeleton(
            PlanSkeletonParams(
                profile = env.recomputer.resolvedProfile(),
                goal = created.plan.goal,
                weeks = created.plan.weeks.size,
                startDate = created.plan.startDate,
                planId = created.plan.id,
                createdAt = created.plan.createdAt,
                seed = seed
            )
        )
        if (created.repairs.isEmpty()) assertEquals(expected, created.plan)
        val firstWeekMin = created.plan.weeks.first().days.sumOf { d -> d.sessions.sumOf { (it.targetDurationSec ?: 0.0) / 60 } }
        assertTrue(firstWeekMin <= maxOf(60.0, seed.weeklyMinutes) * 1.1, "week 1 starts at recent volume: $firstWeekMin vs $seed")
        assertTrue(validatePlan(created.plan, repo.guardrailContext()).valid)
    }

    @Test
    fun refusesToSaveAnInvalidPlan() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "plan.preferences_pb") }
        val repo = PlanRepository(store, env.holder, env.prefs, env.clock, env.recomputer, dispatcher)
        val plan = assertIs<PlanResult.Created>(repo.create(PlanRequest(RaceDistance.TEN_K, LocalDate.parse("2027-01-31")))).plan
        val broken = plan.copy(
            weeks = plan.weeks.map { w ->
                w.copy(days = w.days.filter { d -> d.sessions.none { it.type.name == "REST" } })
            }
        )
        assertFailsWith<IllegalArgumentException> { repo.save(StoredPlan(broken, repo.guardrailContext())) }
        assertEquals(plan, repo.plan.first())
    }
}
