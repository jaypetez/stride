package org.stride.feature.coach

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stride.core.data.testing.InMemoryPreferences
import org.stride.core.data.testing.flakyPreferences
import org.stride.core.datastore.SecretCipher
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.UnitSystem
import org.stride.core.testing.MainDispatcherRule

/** Enabling, asking and the number post-check with a fake LLM (MVP-019). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private object Cipher : SecretCipher {
        override fun encrypt(plaintext: String, associatedData: String) = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String, associatedData: String) = ciphertext.removePrefix("enc:").reversed()
    }

    private class FakeLlm(var reply: CoachLlmResult) : CoachLlm {
        val calls = mutableListOf<Triple<String, String, String>>()

        override suspend fun complete(apiKey: String, system: String, user: String): CoachLlmResult {
            calls += Triple(apiKey, system, user)
            return reply
        }
    }

    private object Facts : CoachFactsSource {
        override suspend fun activity(activityId: Long) =
            if (activityId == 42L) buildActivityFacts(Samples.detail, UnitSystem.METRIC, java.time.ZoneOffset.UTC) else null

        override suspend fun trend() = buildTrendFacts(Samples.today, 48.27, UnitSystem.METRIC)
    }

    private inner class Env(reply: CoachLlmResult = CoachLlmResult.Ok("Fitness is 52.4. Form is -8.7.")) {
        val prefs = UserPreferences(InMemoryPreferences())
        val secrets =
            SecretStore(InMemoryPreferences(), Cipher)
        val llm = FakeLlm(reply)

        fun vm() = CoachViewModel(prefs, secrets, Facts, llm)
    }

    @Test
    fun offByDefaultAndEnablingNeedsCaveatAndKey() = runTest(main.testDispatcher) {
        val env = Env()
        val vm = env.vm()
        assertFalse(vm.uiState.first { !it.loading }.enabled)

        vm.onEvent(CoachEvent.KeyChanged("sk-ant-1"))
        vm.onEvent(CoachEvent.Enable)
        assertNotNull(vm.uiState.value.error, "caveat must be accepted first")
        assertNull(env.secrets.anthropicKey())

        vm.onEvent(CoachEvent.CaveatAccepted(true))
        vm.onEvent(CoachEvent.Enable)
        assertTrue(vm.uiState.first { it.enabled }.enabled)
        assertEquals("sk-ant-1", env.secrets.anthropicKey())
        assertTrue(env.prefs.current().coachEnabled)
        assertEquals("", vm.uiState.value.keyInput)
    }

    @Test
    fun askSendsFactsAndShowsCheckedAnswer() = runTest(main.testDispatcher) {
        val env = Env(CoachLlmResult.Ok("Fitness is 52.4. Form is -8.7, neutral. Aim for 70 km next week."))
        env.secrets.saveAnthropicKey("sk-ant-1")
        env.prefs.setCoachEnabled(true)
        val vm = env.vm()
        vm.setActivity(null)
        val ready = vm.uiState.first { it.enabled && it.facts != null }
        assertEquals(CoachSubject.TREND, ready.subject)

        vm.onEvent(CoachEvent.Ask)
        val answered = vm.uiState.first { it.answer != null }
        assertEquals("Fitness is 52.4. Form is -8.7, neutral.", answered.answer!!.text)
        assertEquals(listOf("Aim for 70 km next week."), answered.answer?.removed)
        val (key, system, user) = env.llm.calls.single()
        assertEquals("sk-ant-1", key)
        assertEquals(COACH_SYSTEM_PROMPT, system)
        assertEquals(coachUserPrompt(ready.facts!!), user)
    }

    @Test
    fun activityRouteExplainsThatActivity() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveAnthropicKey("k")
        env.prefs.setCoachEnabled(true)
        val vm = env.vm()
        vm.setActivity(42)
        val state = vm.uiState.first { it.facts != null }
        assertEquals(CoachSubject.ACTIVITY, state.subject)
        assertEquals(CoachFacts.SUBJECT_ACTIVITY, state.facts!!.subject)
    }

    @Test
    fun unverifiableAnswerIsHidden() = runTest(main.testDispatcher) {
        val env = Env(CoachLlmResult.Ok("Run 70 km. Your marathon time is 3:05:00."))
        env.secrets.saveAnthropicKey("k")
        env.prefs.setCoachEnabled(true)
        val vm = env.vm()
        vm.setActivity(null)
        vm.uiState.first { it.enabled && it.facts != null }
        vm.onEvent(CoachEvent.Ask)
        val state = vm.uiState.first { !it.asking && it.error != null }
        assertNull(state.answer)
    }

    @Test
    fun llmErrorsAreShown() = runTest(main.testDispatcher) {
        val env = Env(CoachLlmResult.Err(CoachLlmError.InvalidKey))
        env.secrets.saveAnthropicKey("k")
        env.prefs.setCoachEnabled(true)
        val vm = env.vm()
        vm.setActivity(null)
        vm.uiState.first { it.enabled && it.facts != null }
        vm.onEvent(CoachEvent.Ask)
        assertEquals(CoachLlmError.InvalidKey.message, vm.uiState.first { it.error != null }.error)
    }

    /** AUD-032: unreadable settings show the error state; "Try again" recovers. */
    @Test
    fun settingsErrorShowsErrorStateAndRetryRecovers() = runTest(main.testDispatcher) {
        val env = Env()
        val (flaky, _) = flakyPreferences(InMemoryPreferences())
        val vm = CoachViewModel(flaky, env.secrets, Facts, env.llm)
        assertTrue(vm.uiState.first { !it.loading }.loadFailed)
        vm.onEvent(CoachEvent.Retry)
        val state = vm.uiState.first { !it.loading }
        assertFalse(state.loadFailed)
        assertFalse(state.enabled)
    }

    @Test
    fun disableDeletesTheKey() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveAnthropicKey("k")
        env.prefs.setCoachEnabled(true)
        val vm = env.vm()
        vm.uiState.first { it.enabled }
        vm.onEvent(CoachEvent.Disable)
        vm.uiState.first { !it.enabled }
        advanceUntilIdle()
        assertNull(env.secrets.anthropicKey())
        // The key is cleared before the flag, so wait for the flag rather than reading it once.
        assertFalse(env.prefs.settings.first { !it.coachEnabled }.coachEnabled)
    }

    /** AUD-030: a slow load for an old subject never overwrites the facts for the newer one. */
    @Test
    fun rapidSubjectChangesNeverShowStaleFacts() = runTest(main.testDispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowActivity = object : CoachFactsSource {
            override suspend fun activity(activityId: Long): CoachFacts? {
                gate.await()
                return Facts.activity(activityId)
            }

            override suspend fun trend() = Facts.trend()
        }
        val env = Env()
        val vm = CoachViewModel(env.prefs, env.secrets, slowActivity, env.llm)
        vm.setActivity(42) // starts the slow activity load
        vm.onEvent(CoachEvent.SubjectChosen(CoachSubject.TREND))
        val trend = vm.uiState.first { it.facts != null }
        assertEquals(CoachFacts.SUBJECT_TREND, trend.facts!!.subject)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(CoachFacts.SUBJECT_TREND, vm.uiState.value.facts!!.subject, "the cancelled activity load must not land")
        assertEquals(CoachSubject.TREND, vm.uiState.value.subject)
    }
}
