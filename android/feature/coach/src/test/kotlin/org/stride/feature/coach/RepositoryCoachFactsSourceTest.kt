package org.stride.feature.coach

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.model.UnitSystem

/** The coach's facts come from the real repositories over the demo pipeline (AUD-038). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryCoachFactsSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun DemoDataFixture.source() = RepositoryCoachFactsSource(activities, insights, prefs, clock)

    @Test
    fun noDataMeansNoFacts() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        assertNull(fx.source().trend())
        assertNull(fx.source().activity(123L))
    }

    @Test
    fun activityFactsUseTheDetailAndTheUserUnits() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        fx.prefs.setUnitSystem(UnitSystem.IMPERIAL)
        val run = fx.activities.observeActivities().first().first { it.activity.sport.isRun }
        val detail = assertNotNull(fx.activities.observeDetail(run.activity.id).first())

        val facts = assertNotNull(fx.source().activity(run.activity.id))
        assertEquals(buildActivityFacts(detail, UnitSystem.IMPERIAL, fx.clock.zone()), facts)
        assertEquals("mi", facts.distanceUnit)
        assertNull(fx.source().activity(-1L), "unknown activity")
    }

    @Test
    fun trendFactsUseInsightsAndCurrentVdot() = runTest {
        val fx = DemoDataFixture(this, tmp.root)
        fx.loadDemo()
        val facts = assertNotNull(fx.source().trend())
        assertEquals(buildTrendFacts(fx.insights.today(), fx.insights.fitness().currentVdot, UnitSystem.METRIC), facts)
        assertEquals("fitness_trend", facts.subject)
    }
}
