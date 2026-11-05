package org.stride.feature.coach

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.stride.core.common.StrideClock
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.data.repository.ActivityRepository
import org.stride.core.datastore.UserPreferences

/** Where the coach's facts come from; faked in tests. */
interface CoachFactsSource {
    /** Facts for one activity, or null when it doesn't exist. */
    suspend fun activity(activityId: Long): CoachFacts?

    /** Facts for the fitness trend, or null when there is no training data yet. */
    suspend fun trend(): CoachFacts?
}

class RepositoryCoachFactsSource @Inject constructor(
    private val activities: ActivityRepository,
    private val insights: InsightsRepository,
    private val prefs: UserPreferences,
    private val clock: StrideClock
) : CoachFactsSource {
    override suspend fun activity(activityId: Long): CoachFacts? {
        val detail = activities.observeDetail(activityId).first() ?: return null
        return buildActivityFacts(detail, prefs.current().unitSystem, clock.zone())
    }

    override suspend fun trend(): CoachFacts? {
        val today = insights.today()
        if (today.form == null && today.thisWeek.runs == 0 && today.lastWeek.runs == 0) return null
        return buildTrendFacts(today, insights.fitness().currentVdot, prefs.current().unitSystem)
    }
}
