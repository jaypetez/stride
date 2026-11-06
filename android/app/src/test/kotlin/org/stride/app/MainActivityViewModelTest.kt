package org.stride.app

import app.cash.turbine.test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.stride.core.data.demo.DemoController
import org.stride.core.data.testing.InMemoryDataStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.testing.MainDispatcherRule

/** The shell's gate state and the demo banner's exit action (AUD-038). */
class MainActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakeDemo(var failure: Throwable? = null) : DemoController {
        var exits = 0

        override suspend fun enter() = Unit

        override suspend fun exit() {
            exits++
            failure?.let { throw it }
        }
    }

    @Test
    fun loadingUntilDataStoreEmitsThenReadyWithEveryChange() = runTest {
        val store = InMemoryDataStore(startReady = false)
        val prefs = UserPreferences(store)
        val vm = MainActivityViewModel(prefs, FakeDemo())
        vm.uiState.test {
            assertEquals(MainUiState.Loading, awaitItem())
            store.ready.complete(Unit)
            val ready = assertIs<MainUiState.Ready>(awaitItem())
            assertEquals(false, ready.settings.onboardingComplete)

            prefs.setDemoMode(true)
            assertTrue(assertIs<MainUiState.Ready>(awaitItem()).settings.demoMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun staysLoadingWithoutSubscribers() = runTest {
        val vm = MainActivityViewModel(UserPreferences(InMemoryDataStore()), FakeDemo())
        assertEquals(MainUiState.Loading, vm.uiState.value, "WhileSubscribed: nothing is read until the UI collects")
    }

    @Test
    fun exitDemoDelegatesToTheController() = runTest {
        val demo = FakeDemo()
        val vm = MainActivityViewModel(UserPreferences(InMemoryDataStore()), demo)
        vm.exitDemo()
        assertEquals(1, demo.exits)
    }

    @Test
    fun exitDemoFailureDoesNotCrashAndAsksTheUserToRetry() = runTest {
        val demo = FakeDemo(failure = IllegalStateException("wipe failed"))
        val prefs = UserPreferences(InMemoryDataStore())
        prefs.setDemoMode(true)
        val vm = MainActivityViewModel(prefs, demo)
        vm.messages.test {
            vm.exitDemo()
            assertEquals(R.string.demo_exit_failed, awaitItem())
            expectNoEvents()
        }
        assertEquals(1, demo.exits)
        assertTrue(prefs.current().demoMode, "demo mode stays on, so the banner is still there to retry")

        // The failure released the guard: a retry reaches the controller again and can succeed.
        demo.failure = null
        vm.exitDemo()
        assertEquals(2, demo.exits)
    }

    @Test
    fun exitDemoSuccessSendsNoMessage() = runTest {
        val vm = MainActivityViewModel(UserPreferences(InMemoryDataStore()), FakeDemo())
        vm.messages.test {
            vm.exitDemo()
            expectNoEvents()
        }
    }
}
