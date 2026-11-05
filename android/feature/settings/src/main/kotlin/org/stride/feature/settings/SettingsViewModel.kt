package org.stride.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import org.stride.core.common.StrideClock
import org.stride.core.data.demo.DemoController
import org.stride.core.data.export.DataExporter
import org.stride.core.data.local.DataEraser
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.AthleteProfile
import org.stride.core.model.UnitSystem

/** Sync windows offered in Settings (UserPreferences accepts 28..730 days). */
val SYNC_WINDOWS = listOf(90, 180, 365)

data class SettingsUiState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val dynamicColor: Boolean = false,
    val demoMode: Boolean = false,
    val syncWindowDays: Int = 180,
    /** Already formatted in the device zone, or null before the first sync. */
    val lastSync: String? = null,
    val garminConnected: Boolean = false,
    val garminKeyRejected: Boolean = false,
    val coachEnabled: Boolean = false,
    val syncing: Boolean = false,
    val syncStatus: String? = null,
    val version: String = "",
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val message: String? = null
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
}

/** One-shot effects for the screen. */
sealed interface SettingsEffect {
    /** Everything was deleted: reset navigation (the shell shows onboarding again). */
    data object DataWiped : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val secrets: SecretStore,
    private val sync: SyncScheduler,
    private val demo: DemoController,
    private val exporter: DataExporter,
    private val eraser: DataEraser,
    private val writer: DocumentWriter,
    private val clock: StrideClock,
    appInfo: AppInfo
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState(version = appInfo.versionName))
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(prefs.settings, secrets.garminConnection, sync.progress) { settings, garmin, progress ->
                Triple(settings, garmin, progress)
            }.collect { (settings, garmin, progress) ->
                state.update {
                    it.copy(
                        loading = false,
                        profile = settings.profile,
                        dynamicColor = settings.dynamicColor,
                        demoMode = settings.demoMode,
                        syncWindowDays = settings.syncWindowDays,
                        lastSync = settings.lastSyncEpochSec?.let(::formatInstant),
                        garminConnected = garmin.connected,
                        garminKeyRejected = settings.garminKeyRejected,
                        coachEnabled = settings.coachEnabled,
                        syncing = progress is SyncProgress.Running,
                        syncStatus = progress.describe()
                    )
                }
            }
        }
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

            SettingsEvent.EnterDemo -> busy("Demo data loaded.") { demo.enter() }

            SettingsEvent.ExitDemo -> busy("Demo data removed.") { demo.exit() }

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
        }
    }

    /** Writes the JSON export to the document the user picked (SAF). */
    fun export(uri: Uri) = busy("Export saved.") {
        val json = exporter.exportJson()
        writer.write(uri, json)
    }

    fun exportFileName(): String = "stride-export-${clock.today()}.json"

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun busy(success: String?, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) success else "Something went wrong. Please try again."
                )
            }
        }
    }

    private fun formatInstant(epochSec: Long): String =
        LAST_SYNC_FORMAT.format(Instant.ofEpochSecond(epochSec).atZone(clock.zone()))

    private companion object {
        val LAST_SYNC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.UK)
    }
}

private fun SyncProgress.describe(): String? = when (this) {
    SyncProgress.Idle -> null
    is SyncProgress.Running -> "Syncing: $phase"
    is SyncProgress.Succeeded -> if (newActivities == 1) "Synced 1 new activity" else "Synced $newActivities new activities"
    is SyncProgress.Failed -> reason.message
}
