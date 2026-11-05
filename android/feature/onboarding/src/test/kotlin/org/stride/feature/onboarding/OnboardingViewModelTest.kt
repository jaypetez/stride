package org.stride.feature.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stride.core.data.demo.DemoController
import org.stride.core.datastore.UserPreferences
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.testing.FakeStrideClock
import org.stride.core.testing.MainDispatcherRule
import org.stride.core.ui.profile.ProfileField
import org.stride.core.ui.profile.ProfileFormState

/** The onboarding flow's state machine and what it persists (MVP-013). Pure JVM: DataStore runs on a temp file. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDemo : DemoController {
        var entered = 0
        var exited = 0
        val active get() = entered > exited

        override suspend fun enter() {
            entered++
        }

        override suspend fun exit() {
            exited++
        }
    }

    /** A DataStore whose writes fail on demand (disk full, I/O error). */
    private class FlakyStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        var failWrites = false
        override val data: Flow<Preferences> = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw java.io.IOException("disk full")
            return inner.updateData(transform)
        }
    }

    private fun TestScope.prefs() = UserPreferences(
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tmp.root, "p.preferences_pb")
        }
    )

    private fun OnboardingViewModel.walkToFinish(parqYes: Set<Int> = emptySet()) {
        onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.ProfileChanged(ProfileFormState(name = "Ada", birthYear = "1990", maxHr = "188")))
        onEvent(OnboardingEvent.Next)
        PARQ_QUESTIONS.indices.forEach { onEvent(OnboardingEvent.ParqAnswered(it, yes = it in parqYes)) }
        onEvent(OnboardingEvent.Next)
        onEvent(OnboardingEvent.DisclaimerAccepted(true))
        onEvent(OnboardingEvent.Next)
    }

    @Test
    fun invalidProfileBlocksAndShowsErrors() = runTest(main.testDispatcher) {
        val vm = OnboardingViewModel(prefs(), FakeDemo(), FakeStrideClock())
        vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.Next)
        val state = vm.uiState.value
        assertEquals(OnboardingStep.PROFILE, state.step)
        assertTrue(ProfileField.NAME in state.profileErrors)
    }

    @Test
    fun parqAndDisclaimerMustBeAnswered() = runTest(main.testDispatcher) {
        val vm = OnboardingViewModel(prefs(), FakeDemo(), FakeStrideClock())
        vm.onEvent(OnboardingEvent.Next)
        vm.onEvent(OnboardingEvent.ProfileChanged(ProfileFormState(name = "Ada", birthYear = "1990")))
        vm.onEvent(OnboardingEvent.Next)
        assertEquals(OnboardingStep.PARQ, vm.uiState.value.step)
        vm.onEvent(OnboardingEvent.ParqAnswered(0, yes = false))
        vm.onEvent(OnboardingEvent.Next)
        assertEquals(OnboardingStep.PARQ, vm.uiState.value.step, "all questions must be answered")
        PARQ_QUESTIONS.indices.forEach { vm.onEvent(OnboardingEvent.ParqAnswered(it, yes = it == 3)) }
        assertFalse(vm.uiState.value.screening.cleared)
        vm.onEvent(OnboardingEvent.Next)
        assertEquals(OnboardingStep.DISCLAIMER, vm.uiState.value.step)
        vm.onEvent(OnboardingEvent.Next)
        assertEquals(OnboardingStep.DISCLAIMER, vm.uiState.value.step, "disclaimer must be accepted")
        assertTrue(vm.back())
        assertEquals(OnboardingStep.PARQ, vm.uiState.value.step)
    }

    @Test
    fun connectGarminPersistsProfileAndFlag() = runTest(main.testDispatcher) {
        val prefs = prefs()
        val demo = FakeDemo()
        val vm = OnboardingViewModel(prefs, demo, FakeStrideClock())
        vm.walkToFinish(parqYes = setOf(2))
        assertEquals(OnboardingStep.FINISH, vm.uiState.value.step)
        vm.destinations.test {
            vm.onEvent(OnboardingEvent.ConnectGarmin)
            assertEquals(ConnectGarminRoute, awaitItem())
        }
        val settings = prefs.settings.first { it.onboardingComplete }
        assertTrue(settings.disclaimerAccepted)
        assertTrue(settings.parqFlagged)
        assertEquals("Ada", settings.profile.name)
        assertEquals(188, settings.profile.maxHr)
        assertEquals(0, demo.entered)
    }

    @Test
    fun tryDemoEntersDemoMode() = runTest(main.testDispatcher) {
        val prefs = prefs()
        val demo = FakeDemo()
        val vm = OnboardingViewModel(prefs, demo, FakeStrideClock())
        vm.walkToFinish()
        vm.destinations.test {
            vm.onEvent(OnboardingEvent.TryDemo)
            assertEquals(TodayRoute, awaitItem())
        }
        assertEquals(1, demo.entered)
        assertFalse(prefs.settings.first { it.onboardingComplete }.parqFlagged)
    }

    /** AUD-030: onboarding is persisted before the hand-off, so the shell never opens half set up. */
    @Test
    fun handOffHappensAfterOnboardingIsPersisted() = runTest(main.testDispatcher) {
        val prefs = prefs()
        val vm = OnboardingViewModel(prefs, FakeDemo(), FakeStrideClock())
        vm.walkToFinish()
        vm.destinations.test {
            vm.onEvent(OnboardingEvent.TryDemo)
            assertEquals(TodayRoute, awaitItem())
            assertTrue(prefs.current().onboardingComplete, "persisted before the hand-off")
        }
    }

    /** AUD-030: if saving fails after the demo loaded, the demo is left again: never demo-on but onboarding-incomplete. */
    @Test
    fun failedSaveAfterDemoRollsBackAndShowsError() = runTest(main.testDispatcher) {
        val store = FlakyStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "f.preferences_pb") })
        val prefs = UserPreferences(store)
        val demo = FakeDemo()
        val vm = OnboardingViewModel(prefs, demo, FakeStrideClock())
        vm.walkToFinish()
        store.failWrites = true
        vm.onEvent(OnboardingEvent.TryDemo)
        val state = vm.uiState.first { it.error != null }
        assertFalse(state.working)
        assertEquals(1, demo.entered)
        assertFalse(demo.active, "demo mode is rolled back")
        assertFalse(prefs.current().onboardingComplete)
    }
}
