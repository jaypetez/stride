package org.stride.feature.settings

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.model.AthleteProfile
import org.stride.core.testing.MainDispatcherRule

/** AUD-032: Edit profile shows an error with "Try again" when the profile cannot be read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileEditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun loadErrorShowsErrorStateAndRetryRecovers() = runTest(main.testDispatcher) {
        val fx = DemoDataFixture(this, tmp.root)
        fx.prefs.updateProfile(AthleteProfile(name = "Ada"))
        val (flaky, _) = fx.flakyPrefs()
        val vm = ProfileEditViewModel(flaky, fx.recomputer, fx.clock, backgroundScope)
        assertTrue(vm.uiState.first { !it.loading }.loadFailed)

        vm.load()
        val state = vm.uiState.first { !it.loading }
        assertFalse(state.loadFailed)
        assertEquals("Ada", state.form.name)
    }
}
