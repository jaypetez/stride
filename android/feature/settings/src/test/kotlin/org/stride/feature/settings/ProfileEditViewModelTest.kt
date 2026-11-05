package org.stride.feature.settings

import app.cash.turbine.test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.data.testing.InMemoryDataStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.AthleteProfile
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem
import org.stride.core.testing.FakeStrideClock
import org.stride.core.testing.MainDispatcherRule
import org.stride.core.ui.UiText
import org.stride.core.ui.profile.ProfileField

/**
 * Load, validate, save and recompute for Settings → Edit profile (AUD-038), and the
 * load error with "Try again" (AUD-032).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileEditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val saved = AthleteProfile(name = "Ada", birthYear = 1990, sex = Sex.FEMALE, maxHr = 188, thresholdPaceSecPerKm = 255.0)

    private class Env(ready: Boolean = true) {
        val store = InMemoryDataStore(ready)
        val prefs = UserPreferences(store)
        val recomputes = mutableListOf<Pair<Set<Long>?, String?>>()

        fun vm(appScope: CoroutineScope) = ProfileEditViewModel(
            prefs,
            { ids, from -> recomputes += ids to from },
            FakeStrideClock(),
            appScope
        )
    }

    @Test
    fun loadsTheSavedProfileIntoTheForm() = runTest {
        val env = Env()
        env.prefs.updateProfile(saved)
        val state = env.vm(CoroutineScope(main.testDispatcher)).uiState.first { !it.loading }
        assertEquals("Ada", state.form.name)
        assertEquals("1990", state.form.birthYear)
        assertEquals("188", state.form.maxHr)
        assertTrue(state.errors.isEmpty())
    }

    @Test
    fun invalidFormShowsErrorsAndDoesNotSave() = runTest {
        val env = Env()
        env.prefs.updateProfile(saved)
        val vm = env.vm(CoroutineScope(main.testDispatcher))
        val loaded = vm.uiState.first { !it.loading }
        vm.onChange(loaded.form.copy(name = " ", maxHr = "300"))
        assertTrue(vm.uiState.value.errors.isEmpty(), "errors appear only after the first save attempt")
        vm.save()
        val errors = vm.uiState.value.errors
        assertTrue(ProfileField.NAME in errors)
        assertTrue(ProfileField.MAX_HR in errors)
        assertFalse(vm.uiState.value.saving)
        assertEquals("Ada", env.prefs.current().profile.name)
        assertTrue(env.recomputes.isEmpty())

        // Once errors are showing, edits re-validate live and clear fixed fields.
        vm.onChange(vm.uiState.value.form.copy(name = "Ada L"))
        assertFalse(ProfileField.NAME in vm.uiState.value.errors)
        assertTrue(ProfileField.MAX_HR in vm.uiState.value.errors)
    }

    @Test
    fun validSavePersistsKeepsUneditedFieldsRecomputesAndSignals() = runTest {
        val env = Env()
        env.prefs.updateProfile(saved)
        val vm = env.vm(CoroutineScope(main.testDispatcher))
        val loaded = vm.uiState.first { !it.loading }
        vm.saved.test {
            vm.onChange(loaded.form.copy(name = "  Ada Lovelace ", units = UnitSystem.IMPERIAL, restingHr = "48"))
            vm.save()
            awaitItem()
        }
        assertTrue(vm.uiState.value.saving)
        val profile = env.prefs.current().profile
        assertEquals("Ada Lovelace", profile.name)
        assertEquals(UnitSystem.IMPERIAL, profile.unitSystem)
        assertEquals(48, profile.restingHr)
        assertEquals(255.0, profile.thresholdPaceSecPerKm, "fields the form doesn't edit are kept")

        assertEquals(listOf<Pair<Set<Long>?, String?>>(null to null), env.recomputes, "full rebuild")
    }

    @Test
    fun saveIsIgnoredWhileSaving() = runTest {
        val env = Env()
        env.prefs.updateProfile(saved)
        val vm = env.vm(CoroutineScope(main.testDispatcher))
        vm.uiState.first { !it.loading }
        vm.save()
        vm.save()

        assertEquals(1, env.recomputes.size)
    }

    @Test
    fun saveIsIgnoredBeforeTheProfileLoads() = runTest {
        val env = Env(ready = false)
        val vm = ProfileEditViewModel(env.prefs, { _, _ -> error("must not recompute") }, FakeStrideClock(), backgroundScope)
        // DataStore has not emitted yet, so the form is still loading.
        assertTrue(vm.uiState.value.loading)
        vm.save()
        assertFalse(vm.uiState.value.saving)
        env.store.ready.complete(Unit)
        assertNull(vm.uiState.first { !it.loading }.errors[ProfileField.NAME], "an ignored save shows no errors")
    }

    @Test
    fun aFailedWriteClearsSavingShowsAnErrorAndDoesNotRecomputeOrNavigate() = runTest {
        val env = Env()
        env.prefs.updateProfile(saved)
        val vm = env.vm(CoroutineScope(main.testDispatcher))
        val loaded = vm.uiState.first { !it.loading }
        env.store.failWrites = java.io.IOException("disk full")
        vm.saved.test {
            vm.onChange(loaded.form.copy(name = "Ada Lovelace"))
            vm.save()
            expectNoEvents()
        }
        val state = vm.uiState.value
        assertFalse(state.saving, "the Save button is usable again")
        assertEquals(UiText(R.string.settings_profile_save_failed), state.saveError)
        assertEquals("Ada Lovelace", state.form.name, "the user's edits are kept for a retry")
        assertTrue(env.recomputes.isEmpty(), "nothing changed, so nothing is recomputed")
        assertEquals("Ada", env.prefs.current().profile.name)

        // Retrying after the fault clears the error and saves.
        env.store.failWrites = null
        vm.saved.test {
            vm.save()
            awaitItem()
        }
        assertNull(vm.uiState.value.saveError)
        assertEquals("Ada Lovelace", env.prefs.current().profile.name)
        assertEquals(1, env.recomputes.size)
    }

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
