package org.stride.feature.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.stride.core.common.AppInfo
import org.stride.core.common.Retryable
import org.stride.core.common.StrideClock
import org.stride.core.data.demo.DemoController
import org.stride.core.data.export.DataExporter
import org.stride.core.data.local.DataEraser
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.data.sync.orLastFailure
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.AthleteProfile
import org.stride.core.model.UnitSystem
import org.stride.core.ui.UiText

/** Sync windows offered in Settings (UserPreferences accepts 28..730 days). */
val SYNC_WINDOWS = listOf(90, 180, 365)

data class SettingsUiState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val dynamicColor: Boolean = false,
    val demoMode: Boolean = false,
    val syncWindowDays: Int = 180,
    /** When the last sync succeeded (null before the first); the screen formats it for the device locale. */
    val lastSync: Instant? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val garminConnected: Boolean = false,
    val garminKeyRejected: Boolean = false,
    val coachEnabled: Boolean = false,
    val syncing: Boolean = false,
    val syncStatus: UiText? = null,
    val version: String = "",
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val message: UiText? = null,
    /** Settings could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
) {
    val units: UnitSystem get() = profile.unitSystem
}

sealed interface SettingsEvent {
    data class UnitsChanged(val units: UnitSystem) : SettingsEvent

    data class DynamicColorChanged(val enabled: Boolean) : SettingsEvent

    data class SyncWindowChanged(val days: Int) : SettingsEvent

    data object SyncNow : SettingsEvent

    data object EnterDemo : SettingsEvent

    data object ExitDemo : SettingsEvent

    data object TurnOffCoach : SettingsEvent

    data object DeleteClicked : SettingsEvent

    data object DeleteDismissed : SettingsEvent

    data object DeleteConfirmed : SettingsEvent

    data object MessageShown : SettingsEvent

    data object Retry : SettingsEvent
}

/** One-shot effects for the screen. */
sealed interface SettingsEffect {
    /** Everything was deleted: reset navigation (the shell shows onboarding again). */
    data object DataWiped : SettingsEffect
}

@HiltViewModel
@Suppress("LongParameterList") // one injected collaborator per Settings section
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val secrets: SecretStore,
    private val sync: SyncScheduler,
    private val demo: DemoController,
    private val exporter: DataExporter,
    private val eraser: DataEraser,
    private val writer: DocumentWriter,
    private val clock: StrideClock,
    appInfo: AppInfo,
    private val savedState: SavedStateHandle
) : ViewModel() {
    // The delete confirmation survives process death (AUD-033).
    private val state = MutableStateFlow(
        SettingsUiState(version = appInfo.versionName, confirmDelete = savedState.get<Boolean>(KEY_CONFIRM_DELETE) ?: false)
    )
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    private val retryable = Retryable()

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            retryable.flow(onError = { null }) {
                combine(prefs.settings, secrets.garminConnection, sync.progress) { settings, garmin, progress ->
                    // Demo mode never syncs with Garmin, so a failure from a real sync does not apply to it.
                    Triple(settings, garmin, progress.orLastFailure(settings.lastSyncFailure.takeUnless { settings.demoMode }))
                }
            }.collect { loaded ->
                if (loaded == null) {
                    state.update { it.copy(loading = false, loadFailed = true) }
                    return@collect
                }
                val (settings, garmin, progress) = loaded
                state.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        profile = settings.profile,
                        dynamicColor = settings.dynamicColor,
                        demoMode = settings.demoMode,
                        syncWindowDays = settings.syncWindowDays,
                        lastSync = settings.lastSyncEpochSec?.let(Instant::ofEpochSecond),
                        zone = clock.zone(),
                        garminConnected = garmin.connected,
                        garminKeyRejected = settings.garminKeyRejected,
                        coachEnabled = settings.coachEnabled,
                        syncing = progress is SyncProgress.Running,
                        syncStatus = progress.describe()
                    )
                }
            }
        }
        viewModelScope.launch { state.collect { savedState[KEY_CONFIRM_DELETE] = it.confirmDelete } }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.UnitsChanged -> launch { prefs.setUnitSystem(event.units) }

            is SettingsEvent.DynamicColorChanged -> launch { prefs.setDynamicColor(event.enabled) }

            is SettingsEvent.SyncWindowChanged -> launch {
                prefs.setSyncWindowDays(event.days)
                // A longer window back-fills on the next sync; start it now when connected.
                if (state.value.garminConnected) sync.syncNow()
            }

            SettingsEvent.SyncNow -> sync.syncNow()

            SettingsEvent.EnterDemo -> busy(UiText(R.string.settings_demo_loaded)) { demo.enter() }

            SettingsEvent.ExitDemo -> busy(UiText(R.string.settings_demo_removed)) { demo.exit() }

            SettingsEvent.TurnOffCoach -> launch {
                secrets.clearAnthropicKey()
                prefs.setCoachEnabled(false)
            }

            SettingsEvent.DeleteClicked -> state.update { it.copy(confirmDelete = true) }

            SettingsEvent.DeleteDismissed -> state.update { it.copy(confirmDelete = false) }

            SettingsEvent.DeleteConfirmed -> {
                state.update { it.copy(confirmDelete = false) }
                busy(null) {
                    eraser.wipeEverything()
                    effectChannel.send(SettingsEffect.DataWiped)
                }
            }

            SettingsEvent.MessageShown -> state.update { it.copy(message = null) }

            SettingsEvent.Retry -> {
                state.update { it.copy(loading = true, loadFailed = false) }
                retryable.retry()
            }
        }
    }

    /** Writes the JSON export to the document the user picked (SAF). */
    fun export(uri: Uri) = busy(UiText(R.string.settings_export_saved)) {
        val json = exporter.exportJson()
        writer.write(uri, json)
    }

    fun exportFileName(): String = "stride-export-${clock.today()}.json"

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun busy(success: UiText?, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) success else UiText(R.string.settings_failed)
                )
            }
        }
    }
}

private const val KEY_CONFIRM_DELETE = "settings.confirmDelete"

private fun SyncProgress.describe(): UiText? = when (this) {
    SyncProgress.Idle -> null
    is SyncProgress.Running -> UiText(R.string.settings_syncing, UiText(phase.labelRes))
    is SyncProgress.Succeeded -> UiText.plural(R.plurals.settings_synced, newActivities, newActivities)
    is SyncProgress.Failed -> UiText(reason.messageRes)
}
