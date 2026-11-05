package org.stride.feature.settings

import androidx.lifecycle.SavedStateHandle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.data.testing.InMemoryPreferences
import org.stride.core.datastore.SecretCipher
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.testing.MainDispatcherRule
import org.stride.core.ui.UiText

/** AUD-032 / AUD-033: the "You" tab's error state, persisted sync failure and saved delete dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private object Cipher : SecretCipher {
        override fun encrypt(plaintext: String, associatedData: String) = plaintext

        override fun decrypt(ciphertext: String, associatedData: String) = ciphertext
    }

    private object Sync : SyncScheduler {
        override val progress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress.Idle)

        override fun schedulePeriodic() = Unit

        override fun syncNow() = Unit

        override fun cancelAll() = Unit
    }

    private object Demo : DemoController {
        override suspend fun enter() = Unit

        override suspend fun exit() = Unit
    }

    private object Eraser : DataEraser {
        override suspend fun clearSyncedData() = Unit

        override suspend fun wipeEverything() = Unit
    }

    private fun DemoDataFixture.viewModel(prefs: UserPreferences = this.prefs, saved: SavedStateHandle = SavedStateHandle()) =
        SettingsViewModel(
            prefs = prefs,
            secrets = SecretStore(InMemoryPreferences(), Cipher),
            sync = Sync,
            demo = Demo,
            exporter = DataExporter(holder, prefs, clock, dispatcher),
            eraser = Eraser,
            writer = DocumentWriter(context, dispatcher),
            clock = clock,
            appInfo = object : AppInfo {
                override val versionName = "1.0"
            },
            savedState = saved
        )

    @Test
    fun loadErrorShowsErrorStateAndRetryRecovers() = runTest(main.testDispatcher) {
        val fx = DemoDataFixture(this, tmp.root)
        val (flaky, _) = fx.flakyPrefs()
        val vm = fx.viewModel(prefs = flaky)
        assertTrue(vm.uiState.first { !it.loading }.loadFailed)
        vm.onEvent(SettingsEvent.Retry)
        assertFalse(vm.uiState.first { !it.loading }.loadFailed)
    }

    @Test
    fun persistedSyncFailureIsShownAfterProcessDeath() = runTest(main.testDispatcher) {
        val fx = DemoDataFixture(this, tmp.root)
        fx.prefs.setLastSyncFailure(SyncFailure.RATE_LIMITED.name)
        val state = fx.viewModel().uiState.first { !it.loading && it.syncStatus != null }
        assertEquals(UiText(SyncFailure.RATE_LIMITED.messageRes), state.syncStatus)
    }

    @Test
    fun deleteDialogSurvivesProcessDeath() = runTest(main.testDispatcher) {
        val fx = DemoDataFixture(this, tmp.root)
        val saved = SavedStateHandle()
        fx.viewModel(saved = saved).onEvent(SettingsEvent.DeleteClicked)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        assertTrue(fx.viewModel(saved = restored).uiState.value.confirmDelete)

        fx.viewModel(saved = restored).onEvent(SettingsEvent.DeleteDismissed)
        assertFalse(
            fx.viewModel(
                saved = SavedStateHandle(
                    restored.keys().associateWith {
                        restored.get<Any>(it)
                    }
                )
            ).uiState.value.confirmDelete
        )
    }
}
