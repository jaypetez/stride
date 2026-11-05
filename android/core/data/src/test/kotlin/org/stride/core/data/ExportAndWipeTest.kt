package org.stride.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.export.DataExporter
import org.stride.core.data.local.RoomDataEraser
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.datastore.SecretCipher
import org.stride.core.datastore.SecretStore
import org.stride.core.garmin.AccessToken
import org.stride.core.model.AthleteProfile

/** Settings → Export (no key material, everything else present) and Delete all data (MVP-021). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExportAndWipeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversible but not identity, so plaintext keys never appear in the store either. */
    private object ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String, associatedData: String) = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String, associatedData: String) = ciphertext.removePrefix("enc:").reversed()
    }

    private class FakeSync : SyncScheduler {
        var cancelled = 0
        override val progress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress.Idle)

        override fun schedulePeriodic() = Unit

        override fun syncNow() = Unit

        override fun cancelAll() {
            cancelled++
        }
    }

    @Test
    fun exportContainsActivitiesButNeverKeys() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val secrets =
            SecretStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }, ReversingCipher)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher).load(env.db)
        env.prefs.completeOnboarding(AthleteProfile(name = "Ada", birthYear = 1990), parqFlagged = false)
        secrets.saveGarminKey(GARMIN_KEY, null)
        secrets.put(AccessToken(TOKEN, Long.MAX_VALUE))
        secrets.saveAnthropicKey(ANTHROPIC_KEY)

        val json = DataExporter(env.holder, env.prefs, env.clock, dispatcher).exportJson()

        listOf(GARMIN_KEY, TOKEN, ANTHROPIC_KEY, GARMIN_KEY.reversed(), "api_key", "apiKey", "token").forEach {
            assertFalse(json.contains(it, ignoreCase = true), "export must not contain $it")
        }
        val root = Json.parseToJsonElement(json).jsonObject
        val activities = root.getValue("activities").jsonArray
        assertEquals(env.db.activityDao().count(), activities.size)
        assertTrue(activities.isNotEmpty())
        val first = activities.first().jsonObject
        assertTrue("activity" in first && "metrics" in first && "laps" in first)
        assertTrue(root.getValue("wellness").jsonArray.isNotEmpty())
        assertTrue(root.getValue("dailyLoads").jsonArray.isNotEmpty())
        assertTrue(json.contains("\"Ada\""))
    }

    @Test
    fun wipeEverythingLeavesNothingBehind() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val secrets =
            SecretStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }, ReversingCipher)
        val sync = FakeSync()
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher).load(env.db)
        env.prefs.completeOnboarding(AthleteProfile(name = "Ada"), parqFlagged = true)
        env.prefs.setCoachEnabled(true)
        secrets.saveGarminKey(GARMIN_KEY, null)
        secrets.saveAnthropicKey(ANTHROPIC_KEY)

        val plans = org.stride.core.data.plan.PlanRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "plan.preferences_pb") },
            env.holder,
            env.prefs,
            env.clock,
            env.recomputer,
            dispatcher
        )
        RoomDataEraser(env.context, env.holder, env.prefs, secrets, sync, plans, dispatcher).wipeEverything()
        kotlin.test.assertNull(plans.plan.first())

        val db = env.db
        assertEquals(0, db.activityDao().count())
        assertTrue(db.wellnessDao().all().isEmpty())
        assertTrue(db.metricsDao().allActivityMetrics().isEmpty())
        assertTrue(db.metricsDao().allDailyLoads().isEmpty())
        assertNull(db.syncStateDao().get())
        val settings = env.prefs.current()
        assertFalse(settings.onboardingComplete)
        assertFalse(settings.coachEnabled)
        assertEquals(AthleteProfile(), settings.profile)
        assertFalse(secrets.garminConnection.first().connected)
        assertNull(secrets.apiKey())
        assertNull(secrets.anthropicKey())
        assertNull(secrets.get())
        assertEquals(1, sync.cancelled)
    }

    @Test
    fun clearSyncedDataKeepsSettings() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val env = TestEnv(this, dispatcher, tmp.root)
        val secrets =
            SecretStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }, ReversingCipher)
        DemoMode(env.context, env.holder, env.prefs, env.clock, env.recomputer, dispatcher).load(env.db)
        env.prefs.completeOnboarding(AthleteProfile(name = "Ada"), parqFlagged = false)

        RoomDataEraser(
            env.context,
            env.holder,
            env.prefs,
            secrets,
            FakeSync(),
            org.stride.core.data.plan.PlanRepository(
                PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "plan2.preferences_pb") },
                env.holder,
                env.prefs,
                env.clock,
                env.recomputer,
                dispatcher
            ),
            dispatcher
        ).clearSyncedData()

        assertEquals(0, env.db.activityDao().count())
        assertTrue(env.prefs.current().onboardingComplete)
    }

    private companion object {
        const val GARMIN_KEY = "GARMIN-PERSONAL-KEY-0123456789"
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.ACCESS-TOKEN.sig"
        const val ANTHROPIC_KEY = "sk-ant-api03-SECRET-KEY"
    }
}
