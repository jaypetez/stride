package org.stride.feature.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.common.AppInfo
import org.stride.core.data.demo.DemoController
import org.stride.core.data.export.DataExporter
import org.stride.core.data.local.DataEraser
import org.stride.core.data.sync.SyncFailure
import org.stride.core.data.sync.SyncPhase
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.data.testing.InMemoryPreferences
import org.stride.core.datastore.SecretCipher
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.UnitSystem
import org.stride.core.testing.MainDispatcherRule
import org.stride.core.ui.UiText

/**
 * Settings: state mapping, actions, the busy guard and failure paths with fakes (AUD-038),
 * plus the error state, persisted sync failure and saved delete dialog (AUD-032 / AUD-033).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private object Cipher : SecretCipher {
        override fun encrypt(plaintext: String, associatedData: String) = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String, associatedData: String) = ciphertext.removePrefix("enc:").reversed()
    }

    private class FakeScheduler : SyncScheduler {
        val state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
        override val progress: StateFlow<SyncProgress> = state
        var syncNowCalls = 0
        var cancelCalls = 0

        override fun syncNow() {
            syncNowCalls++
        }

        override fun schedulePeriodic() = Unit

        override fun cancelAll() {
            cancelCalls++
        }
    }

    private class FakeDemo : DemoController {
        var enters = 0
        var exits = 0
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null

        override suspend fun enter() {
            enters++
            gate?.await()
            failWith?.let { throw it }
        }

        override suspend fun exit() {
            exits++
            failWith?.let { throw it }
        }
    }

    private class FakeEraser : DataEraser {
        var wipes = 0
        var failWith: Exception? = null

        override suspend fun clearSyncedData() = Unit

        override suspend fun wipeEverything() {
            wipes++
            failWith?.let { throw it }
        }
    }

    private inner class Env(scope: TestScope) {
        val fx = DemoDataFixture(scope, tmp.root)
        val secrets = SecretStore(InMemoryPreferences(), Cipher)
        val sync = FakeScheduler()
        val demo = FakeDemo()
        val eraser = FakeEraser()
        val exporter = DataExporter(fx.holder, fx.prefs, fx.clock, fx.dispatcher)
        val writer = DocumentWriter(fx.context, fx.dispatcher)

        fun vm(prefs: UserPreferences = fx.prefs, saved: SavedStateHandle = SavedStateHandle()) = SettingsViewModel(
            prefs,
            secrets,
            sync,
            demo,
            exporter,
            eraser,
            writer,
            fx.clock,
            object : AppInfo {
                override val versionName = "9.9.9-test"
            },
            saved
        )
    }

    private suspend fun SettingsViewModel.loaded() = uiState.first { !it.loading }

    private val failed = UiText(R.string.settings_failed)

    private fun SavedStateHandle.copy() = SavedStateHandle(keys().associateWith { get<Any>(it) })

    @Test
    fun initialStateReflectsPreferencesSecretsAndSync() = runTest {
        val env = Env(this)
        env.fx.prefs.setLastSync(1_793_000_000)
        env.fx.prefs.setDemoMode(true)
        env.secrets.saveGarminKey("gpk", null)
        env.sync.state.value = SyncProgress.Running(SyncPhase.IMPORTING, 1, 4)
        val state = env.vm().loaded()
        assertEquals("9.9.9-test", state.version)
        assertTrue(state.demoMode)
        assertTrue(state.garminConnected)
        assertTrue(state.syncing)
        assertEquals(UiText(R.string.settings_syncing, UiText(SyncPhase.IMPORTING.labelRes)), state.syncStatus)
        assertEquals(Instant.ofEpochSecond(1_793_000_000), state.lastSync)
        assertEquals(180, state.syncWindowDays)
    }

    @Test
    fun syncStatusIsDescribed() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.loaded()
        val running = UiText(R.string.settings_syncing, UiText(SyncPhase.CHECKING.labelRes))

        suspend fun statusFor(progress: SyncProgress): SettingsUiState {
            env.sync.state.value = SyncProgress.Running(SyncPhase.CHECKING, 0, 1)
            vm.uiState.first { it.syncStatus == running }
            env.sync.state.value = progress
            return vm.uiState.first { !it.loading && it.syncStatus != running }
        }
        assertEquals(UiText.plural(R.plurals.settings_synced, 1, 1), statusFor(SyncProgress.Succeeded(1, 1)).syncStatus)
        assertEquals(UiText.plural(R.plurals.settings_synced, 3, 3), statusFor(SyncProgress.Succeeded(1, 3)).syncStatus)
        val offline = statusFor(SyncProgress.Failed(SyncFailure.OFFLINE))
        assertEquals(UiText(SyncFailure.OFFLINE.messageRes), offline.syncStatus)
        assertFalse(offline.syncing)
        assertNull(statusFor(SyncProgress.Idle).syncStatus)
    }

    @Test
    fun unitsAndDynamicColorArePersisted() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.onEvent(SettingsEvent.UnitsChanged(UnitSystem.IMPERIAL))
        vm.onEvent(SettingsEvent.DynamicColorChanged(true))
        val state = vm.uiState.first { it.units == UnitSystem.IMPERIAL && it.dynamicColor }
        assertTrue(state.dynamicColor)
        assertEquals(UnitSystem.IMPERIAL, env.fx.prefs.current().unitSystem)
    }

    @Test
    fun syncWindowSyncsOnlyWhenConnected() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.loaded()
        vm.onEvent(SettingsEvent.SyncWindowChanged(365))
        assertEquals(365, vm.uiState.first { it.syncWindowDays == 365 }.syncWindowDays)
        assertEquals(0, env.sync.syncNowCalls, "not connected: no sync")

        env.secrets.saveGarminKey("gpk", null)
        vm.uiState.first { it.garminConnected }
        vm.onEvent(SettingsEvent.SyncWindowChanged(90))
        vm.uiState.first { it.syncWindowDays == 90 }
        assertEquals(1, env.sync.syncNowCalls)

        vm.onEvent(SettingsEvent.SyncNow)
        assertEquals(2, env.sync.syncNowCalls)
    }

    @Test
    fun enterAndExitDemoReportSuccess() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.onEvent(SettingsEvent.EnterDemo)
        assertEquals(UiText(R.string.settings_demo_loaded), vm.uiState.first { it.message != null }.message)
        assertFalse(vm.uiState.value.busy)
        vm.onEvent(SettingsEvent.MessageShown)
        assertNull(vm.uiState.value.message)

        vm.onEvent(SettingsEvent.ExitDemo)
        assertEquals(UiText(R.string.settings_demo_removed), vm.uiState.first { it.message != null }.message)
        assertEquals(1, env.demo.enters)
        assertEquals(1, env.demo.exits)
    }

    @Test
    fun busyGuardIgnoresActionsWhileOneRuns() = runTest {
        val env = Env(this)
        val gate = CompletableDeferred<Unit>()
        env.demo.gate = gate
        val vm = env.vm()
        vm.onEvent(SettingsEvent.EnterDemo)
        assertTrue(vm.uiState.value.busy)
        vm.onEvent(SettingsEvent.EnterDemo)
        vm.onEvent(SettingsEvent.ExitDemo)
        vm.onEvent(SettingsEvent.DeleteConfirmed)
        assertEquals(1, env.demo.enters)
        assertEquals(0, env.demo.exits)
        assertEquals(0, env.eraser.wipes)
        gate.complete(Unit)
        assertFalse(vm.uiState.first { !it.busy }.busy)
    }

    @Test
    fun demoFailureShowsAnErrorAndClearsBusy() = runTest {
        val env = Env(this)
        env.demo.failWith = IllegalStateException("boom")
        val vm = env.vm()
        vm.onEvent(SettingsEvent.EnterDemo)
        val state = vm.uiState.first { it.message != null }
        assertEquals(failed, state.message)
        assertFalse(state.busy)
    }

    @Test
    fun turnOffCoachClearsKeyAndFlag() = runTest {
        val env = Env(this)
        env.secrets.saveAnthropicKey("sk-ant")
        env.fx.prefs.setCoachEnabled(true)
        val vm = env.vm()
        assertTrue(vm.loaded().coachEnabled)
        vm.onEvent(SettingsEvent.TurnOffCoach)
        assertFalse(vm.uiState.first { !it.coachEnabled }.coachEnabled)
        assertNull(env.secrets.anthropicKey())
    }

    @Test
    fun deleteDialogAndConfirmedWipeEmitsEffect() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.onEvent(SettingsEvent.DeleteClicked)
        assertTrue(vm.uiState.value.confirmDelete)
        vm.onEvent(SettingsEvent.DeleteDismissed)
        assertFalse(vm.uiState.value.confirmDelete)
        assertEquals(0, env.eraser.wipes)

        vm.effects.test {
            vm.onEvent(SettingsEvent.DeleteClicked)
            vm.onEvent(SettingsEvent.DeleteConfirmed)
            assertEquals(SettingsEffect.DataWiped, awaitItem())
        }
        assertEquals(1, env.eraser.wipes)
        assertFalse(vm.uiState.value.confirmDelete)
        assertNull(vm.uiState.value.message, "wipe has no success toast")
    }

    @Test
    fun failedWipeShowsErrorAndEmitsNoEffect() = runTest {
        val env = Env(this)
        env.eraser.failWith = IllegalStateException("disk")
        val vm = env.vm()
        vm.effects.test {
            vm.onEvent(SettingsEvent.DeleteConfirmed)
            assertEquals(failed, vm.uiState.first { it.message != null }.message)
            expectNoEvents()
        }
    }

    @Test
    fun exportWritesJsonToTheChosenDocument() = runTest {
        val env = Env(this)
        env.fx.loadDemo()
        val vm = env.vm()
        assertEquals("stride-export-2026-11-01.json", vm.exportFileName())
        val out = File(tmp.root, "export.json")
        vm.export(Uri.fromFile(out))
        assertEquals(UiText(R.string.settings_export_saved), vm.uiState.first { it.message != null }.message)
        val json = Json.parseToJsonElement(out.readText()).jsonObject
        assertEquals("stride-export", json["format"]?.jsonPrimitive?.content)
        assertEquals("false", json["demo"]?.jsonPrimitive?.content)
        assertTrue(json["activities"]!!.jsonArray.isNotEmpty(), "the export holds the active database")
    }

    @Test
    fun exportFailureShowsError() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.export(Uri.parse("content://org.stride.missing/export.json"))
        assertEquals(failed, vm.uiState.first { it.message != null }.message)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun loadErrorShowsErrorStateAndRetryRecovers() = runTest(main.testDispatcher) {
        val env = Env(this)
        val (flaky, _) = env.fx.flakyPrefs()
        val vm = env.vm(prefs = flaky)
        assertTrue(vm.uiState.first { !it.loading }.loadFailed)
        vm.onEvent(SettingsEvent.Retry)
        assertFalse(vm.uiState.first { !it.loading }.loadFailed)
    }

    @Test
    fun persistedSyncFailureIsShownAfterProcessDeath() = runTest(main.testDispatcher) {
        val env = Env(this)
        env.fx.prefs.setLastSyncFailure(SyncFailure.RATE_LIMITED.name)
        val state = env.vm().uiState.first { !it.loading && it.syncStatus != null }
        assertEquals(UiText(SyncFailure.RATE_LIMITED.messageRes), state.syncStatus)
    }

    /** AUD-032: demo mode never syncs, so a failure persisted by a real sync is not shown there. */
    @Test
    fun persistedSyncFailureIsHiddenInDemoMode() = runTest(main.testDispatcher) {
        val env = Env(this)
        env.fx.prefs.setLastSyncFailure(SyncFailure.KEY_INVALID.name)
        env.fx.prefs.setDemoMode(true)
        val state = env.vm().uiState.first { !it.loading && it.demoMode }
        assertNull(state.syncStatus)
    }

    @Test
    fun deleteDialogSurvivesProcessDeath() = runTest(main.testDispatcher) {
        val env = Env(this)
        val saved = SavedStateHandle()
        env.vm(saved = saved).onEvent(SettingsEvent.DeleteClicked)
        val restored = saved.copy()
        assertTrue(env.vm(saved = restored).uiState.value.confirmDelete)

        env.vm(saved = restored).onEvent(SettingsEvent.DeleteDismissed)
        assertFalse(env.vm(saved = restored.copy()).uiState.value.confirmDelete)
    }
}
