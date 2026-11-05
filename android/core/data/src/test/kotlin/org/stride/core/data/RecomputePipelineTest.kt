package org.stride.core.data

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.demo.DemoDataGenerator
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.metrics.toScience
import org.stride.core.database.toModel
import org.stride.core.science.load.computeActivityLoad
import org.stride.core.science.pmc.PmcOptions
import org.stride.core.science.pmc.buildPmcSeries
import org.stride.core.science.pmc.toDailyLoads

/** The pipeline stores exactly what core:science computes, over the demo dataset (MVP-012/020). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecomputePipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun close(a: Double, b: Double) = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

    @Test
    fun storedMetricsEqualScienceOutputs() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        val demo = DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, UnconfinedTestDispatcher(testScheduler))
        demo.load(env.db)

        val profile = env.recomputer.resolvedProfile()
        val activities = env.db.activityDao().allChronological().map { it.toModel() }
        val sci = activities.map { a -> a.toScience(env.db.streamDao().get(a.id)?.toModel(), env.clock.zone()) }
        val stored = env.db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        assertEquals(activities.size, stored.size)
        sci.forEach { s ->
            val expected = computeActivityLoad(s, profile)
            assertTrue(close(expected.tss, stored.getValue(s.id.toLong()).load), "load for ${s.id}")
        }
        // Demo runs carry streams with HR and pace → rTSS, zones, EF and decoupling are present.
        val run = stored.values.first { it.loadMethod == "rtss" }
        assertNotNull(run.hrZoneSeconds)
        assertNotNull(run.efficiencyFactor)

        val expectedPmc = buildPmcSeries(toDailyLoads(sci, profile), PmcOptions(throughDate = env.clock.today().toString()))
        val storedPmc = env.db.metricsDao().allDailyLoads()
        assertEquals(expectedPmc.map { it.date }, storedPmc.map { it.date })
        expectedPmc.zip(storedPmc).forEach { (e, s) ->
            assertTrue(close(e.ctl, s.ctl) && close(e.atl, s.atl) && close(e.tsb, s.tsb), "PMC ${e.date}")
        }
    }

    @Test
    fun recomputeIsIdempotentAndIncremental() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, UnconfinedTestDispatcher(testScheduler)).load(env.db)
        val metrics = env.db.metricsDao().allActivityMetrics()
        val loads = env.db.metricsDao().allDailyLoads()
        env.recomputer.recompute(changedActivityIds = emptySet(), fromDate = loads[loads.size - 10].date)
        assertEquals(metrics.sortedBy { it.activityId }, env.db.metricsDao().allActivityMetrics().sortedBy { it.activityId })
        assertEquals(loads, env.db.metricsDao().allDailyLoads())
    }

    @Test
    fun changingProfileAnchorsTriggersFullRecompute() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, UnconfinedTestDispatcher(testScheduler)).load(env.db)
        val before = env.db.metricsDao().allActivityMetrics().associateBy { it.activityId }
        env.prefs.updateProfile(env.prefs.current().profile.copy(thresholdHr = 150, maxHr = 185))
        env.recomputer.recompute(changedActivityIds = emptySet(), fromDate = env.clock.today().toString())
        val after = env.db.metricsDao().allActivityMetrics()
        assertTrue(after.all { it.anchorsHash != before.getValue(it.activityId).anchorsHash })
        assertEquals(ScienceMetricsRecomputer.anchorsHash(env.recomputer.resolvedProfile()), after.first().anchorsHash)
        assertNotEquals(after.map { before.getValue(it.activityId).hrZoneSeconds }, after.map { it.hrZoneSeconds })
    }

    @Test
    fun insightsAreComputedFromStoredSeries() = runTest(UnconfinedTestDispatcher()) {
        val env = TestEnv(this, UnconfinedTestDispatcher(testScheduler), tmp.root)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, UnconfinedTestDispatcher(testScheduler)).load(env.db)
        val insights = InsightsRepository(env.holder, env.prefs, env.clock, env.recomputer, UnconfinedTestDispatcher(testScheduler))
        val today = insights.today()
        assertNotNull(today.form)
        assertNotNull(today.band)
        assertNotNull(today.nextWorkout)
        assertTrue(today.lastWeek.runs > 0)
        val fitness = insights.fitness()
        assertTrue(fitness.pmc.size > 150)
        assertTrue(fitness.weeklyZones.isNotEmpty())
        assertNotNull(fitness.currentVdot)
        assertEquals(4, fitness.predictions.size)
        assertTrue(fitness.predictions.zipWithNext().all { (a, b) -> a.timeSec < b.timeSec })
    }

    @Test
    fun demoDatasetIsDeterministic() {
        val today = java.time.LocalDate.parse("2026-11-01")
        val a = DemoDataGenerator.generate(today)
        val b = DemoDataGenerator.generate(today)
        assertEquals(a.activities.map { it.activity }, b.activities.map { it.activity })
        assertEquals(a.activities.map { it.streams }, b.activities.map { it.streams })
        assertEquals(a.wellness, b.wellness)
        assertTrue(a.activities.size in 90..130, "~26 weeks of runs, got ${a.activities.size}")
        assertTrue(a.activities.all { it.activity.startEpochSec <= today.plusDays(1).toEpochDay() * 86_400 })
    }
}
