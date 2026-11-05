package org.stride.core.data.plan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.database.DatabaseHolder
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.RaceGoal as AppRaceGoal
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.plan.PlanGuardrailContext
import org.stride.core.science.plan.validatePlan

/** A saved plan plus the guardrail context it was validated against. */
@Serializable
data class StoredPlan(val plan: TrainingPlan, val context: PlanGuardrailContext)

/**
 * The user's training plan (MVP-018), stored as JSON in its own DataStore file
 * ([FILE_NAME]). Only plans that pass `validatePlan` are saved, and [plan]
 * re-checks on read, so the UI never shows a plan that breaks the guardrails.
 */
@Singleton
class PlanRepository @Inject constructor(
    @PlanStore private val dataStore: DataStore<Preferences>,
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    private val recomputer: ScienceMetricsRecomputer,
    @Dispatcher(StrideDispatchers.Default) private val dispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }

    val plan: Flow<TrainingPlan?> = dataStore.data.map { p ->
        p[PLAN]?.let { raw -> runCatching { json.decodeFromString(StoredPlan.serializer(), raw) }.getOrNull() }
            ?.takeIf { validatePlan(it.plan, it.context).valid }
            ?.plan
    }

    /** Seeds the CTL-ramp guardrail with today's fitness and fatigue (none without history). */
    suspend fun guardrailContext(): PlanGuardrailContext {
        val today = clock.today().toString()
        val latest = holder.db.metricsDao().allDailyLoads().lastOrNull { it.date <= today }
        val profile = recomputer.resolvedProfile()
        return PlanGuardrailContext(seedCtl = latest?.ctl, seedAtl = latest?.atl, experienceLevel = profile.experienceLevel)
    }

    /** Generates, validates and (when it passes) saves a plan; also records the race goal on the profile. */
    suspend fun create(request: PlanRequest): PlanResult = withContext(dispatcher) {
        val ctx = guardrailContext()
        val now = clock.now()
        val result = generatePlan(
            request = request,
            profile = recomputer.resolvedProfile(),
            today = clock.today(),
            ctx = ctx,
            planId = "plan-${now.epochSecond}",
            createdAt = now.toString()
        )
        if (result is PlanResult.Created) {
            save(StoredPlan(result.plan, ctx))
            val profile = prefs.current().profile
            prefs.updateProfile(
                profile.copy(raceGoal = AppRaceGoal(raceDistanceM(request.distance), request.raceDate.toString(), request.goalTimeSec))
            )
        }
        result
    }

    suspend fun save(stored: StoredPlan) {
        require(validatePlan(stored.plan, stored.context).valid) { "refusing to save a plan that fails the guardrails" }
        dataStore.edit { it[PLAN] = json.encodeToString(StoredPlan.serializer(), stored) }
    }

    suspend fun delete() {
        dataStore.edit { it.remove(PLAN) }
    }

    companion object {
        const val FILE_NAME = "stride_plan"
        private val PLAN = stringPreferencesKey("plan_json")
    }
}
