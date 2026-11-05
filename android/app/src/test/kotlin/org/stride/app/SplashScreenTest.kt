package org.stride.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.stride.core.data.demo.DemoController
import org.stride.core.datastore.UserPreferences
import org.stride.core.testing.MainDispatcherRule

/** AUD-037: the system splash stays up exactly until the settings have loaded. */
class SplashScreenTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** Settings that load only when [release] completes, like a slow first DataStore read. */
    private class SlowStore(private val release: CompletableDeferred<Unit>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            release.await()
            emit(emptyPreferences())
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(emptyPreferences())
    }

    private object NoDemo : DemoController {
        override suspend fun enter() = Unit

        override suspend fun exit() = Unit
    }

    @Test
    fun splashStaysUntilSettingsLoad() = runTest(main.testDispatcher) {
        val release = CompletableDeferred<Unit>()
        val vm = MainActivityViewModel(UserPreferences(SlowStore(release)), NoDemo)
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertTrue(vm.keepSplashOnScreen, "settings not loaded yet: keep the splash")

        release.complete(Unit)
        runCurrent()
        assertFalse(vm.keepSplashOnScreen, "settings loaded: dismiss the splash")
        collector.cancel()
    }
}
