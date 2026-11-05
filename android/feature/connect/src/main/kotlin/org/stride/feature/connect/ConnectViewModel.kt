package org.stride.feature.connect

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.stride.core.common.Retryable
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideLog
import org.stride.core.common.StrideResult
import org.stride.core.data.local.DataEraser
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.garmin.GarminClient
import org.stride.core.garmin.GarminError
import org.stride.core.ui.UiText

/** Personal API key lifetimes Garmin offers (docs/garmin/personal-api.md). */
enum class KeyLifetime(@StringRes val labelRes: Int) {
    DAYS_30(R.string.connect_lifetime_30),
    DAYS_90(R.string.connect_lifetime_90),
    DAYS_180(R.string.connect_lifetime_180),
    YEAR_1(R.string.connect_lifetime_365);

    fun expiryFrom(today: LocalDate): LocalDate = when (this) {
        DAYS_30 -> today.plusDays(30)
        DAYS_90 -> today.plusDays(90)
        DAYS_180 -> today.plusDays(180)
        YEAR_1 -> today.plusYears(1)
    }
}

/** Why a key could not be connected; [message] is a string resource resolved by the screen (AUD-034). */
sealed interface ConnectError {
    val message: UiText

    data object EmptyKey : ConnectError {
        override val message = UiText(R.string.connect_error_empty)
    }

    data object InvalidKey : ConnectError {
        override val message = UiText(R.string.connect_error_invalid)
    }

    data object Network : ConnectError {
        override val message = UiText(R.string.connect_error_network)
    }

    data class Other(override val message: UiText) : ConnectError

    companion object {
        /** Saving or deleting on the phone failed (e.g. the Keystore is unavailable). */
        val SaveFailed = Other(UiText(R.string.connect_error_save_failed))
    }
}

const val EXPIRY_WARNING_DAYS = 14L

data class ConnectUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.of(2026, 1, 1),
    val connected: Boolean = false,
    val keyExpiry: LocalDate? = null,
    /** Garmin rejected the stored key during sync (MVP-008). */
    val keyRejected: Boolean = false,
    /** Saved secrets could not be decrypted (Keystore reset) and must be re-entered (AUD-027). */
    val keyLost: Boolean = false,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    val lifetime: KeyLifetime = KeyLifetime.DAYS_90,
    val expiry: LocalDate = KeyLifetime.DAYS_90.expiryFrom(today),
    val connecting: Boolean = false,
    val error: ConnectError? = null,
    val showDisconnect: Boolean = false,
    val deleteSyncedData: Boolean = false,
    val disconnecting: Boolean = false,
    /** The connection state could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
) {
    val daysLeft: Long? get() = keyExpiry?.let { ChronoUnit.DAYS.between(today, it) }
    val expired: Boolean get() = (daysLeft ?: 0) < 0
    val expiringSoon: Boolean get() = daysLeft?.let { it in 0..EXPIRY_WARNING_DAYS } ?: false

    /** Connected users still see the key form when the key needs replacing. */
    val needsNewKey: Boolean get() = !connected || expired || expiringSoon || keyRejected
}

sealed interface ConnectEvent {
    data class KeyChanged(val key: String) : ConnectEvent

    data object ToggleKeyVisible : ConnectEvent

    data class LifetimeChosen(val lifetime: KeyLifetime) : ConnectEvent

    data class ExpiryPicked(val date: LocalDate) : ConnectEvent

    data object Connect : ConnectEvent

    data object DisconnectClicked : ConnectEvent

    data object DisconnectDismissed : ConnectEvent

    data class DeleteSyncedDataChanged(val delete: Boolean) : ConnectEvent

    data object DisconnectConfirmed : ConnectEvent

    data object Retry : ConnectEvent
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val garmin: GarminClient,
    private val secrets: SecretStore,
    private val prefs: UserPreferences,
    private val sync: SyncScheduler,
    private val eraser: DataEraser,
    private val clock: StrideClock,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val state = MutableStateFlow(restore(clock.today()))
    val uiState: StateFlow<ConnectUiState> = state.asStateFlow()

    private val retryable = Retryable()

    init {
        viewModelScope.launch {
            retryable.flow(onError = { null }) {
                combine(secrets.garminConnection, prefs.settings) { connection, settings -> connection to settings.garminKeyRejected }
            }.collect { loaded ->
                state.update {
                    if (loaded == null) {
                        it.copy(loading = false, loadFailed = true)
                    } else {
                        val (connection, rejected) = loaded
                        it.copy(
                            loading = false,
                            loadFailed = false,
                            today = clock.today(),
                            connected = connection.connected,
                            keyExpiry = connection.keyExpiry,
                            keyRejected = rejected,
                            keyLost = connection.needsReentry
                        )
                    }
                }
            }
        }
        // The dialog and the form choices survive process death; the pasted key never does (AUD-033).
        viewModelScope.launch {
            state.collect { s ->
                savedState[KEY_LIFETIME] = s.lifetime.name
                savedState[KEY_EXPIRY] = s.expiry.toString()
                savedState[KEY_SHOW_DISCONNECT] = s.showDisconnect
                savedState[KEY_DELETE_DATA] = s.deleteSyncedData
            }
        }
    }

    private fun restore(today: LocalDate): ConnectUiState {
        val lifetime = savedState.get<String>(KEY_LIFETIME)?.let { name -> KeyLifetime.entries.firstOrNull { it.name == name } }
            ?: KeyLifetime.DAYS_90
        val expiry =
            savedState.get<String>(KEY_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: lifetime.expiryFrom(today)
        return ConnectUiState(
            today = today,
            lifetime = lifetime,
            expiry = expiry,
            showDisconnect = savedState.get<Boolean>(KEY_SHOW_DISCONNECT) ?: false,
            deleteSyncedData = savedState.get<Boolean>(KEY_DELETE_DATA) ?: false
        )
    }

    fun onEvent(event: ConnectEvent) {
        when (event) {
            // The key is never logged; it only lives in this state until saved encrypted.
            is ConnectEvent.KeyChanged -> state.update { it.copy(keyInput = event.key, error = null) }

            ConnectEvent.ToggleKeyVisible -> state.update { it.copy(keyVisible = !it.keyVisible) }

            is ConnectEvent.LifetimeChosen -> state.update {
                it.copy(lifetime = event.lifetime, expiry = event.lifetime.expiryFrom(clock.today()))
            }

            // The picker only offers today or later; clamp anyway so a stale date can never be saved (AUD-037).
            is ConnectEvent.ExpiryPicked -> state.update { it.copy(expiry = maxOf(event.date, clock.today())) }

            ConnectEvent.Connect -> connect()

            ConnectEvent.DisconnectClicked -> state.update { it.copy(showDisconnect = true, deleteSyncedData = false) }

            ConnectEvent.DisconnectDismissed -> state.update { it.copy(showDisconnect = false) }

            is ConnectEvent.DeleteSyncedDataChanged -> state.update { it.copy(deleteSyncedData = event.delete) }

            ConnectEvent.DisconnectConfirmed -> disconnect()

            ConnectEvent.Retry -> {
                state.update { it.copy(loading = true, loadFailed = false) }
                retryable.retry()
            }
        }
    }

    private fun connect() {
        val current = state.value
        if (current.connecting) return
        val key = current.keyInput.trim()
        if (key.isEmpty()) {
            state.update { it.copy(error = ConnectError.EmptyKey) }
            return
        }
        state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            // Every path clears the spinner, even an unexpected failure (AUD-030).
            try {
                // Validate the pasted key with Garmin before storing anything.
                when (val result = garmin.exchangeToken(key)) {
                    is StrideResult.Ok -> {
                        secrets.saveGarminKey(key, current.expiry)
                        prefs.setGarminKeyRejected(false)
                        sync.syncNow()
                        sync.schedulePeriodic()
                        state.update { it.copy(keyInput = "", keyVisible = false) }
                    }

                    is StrideResult.Err -> state.update { it.copy(error = result.error.toConnectError()) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                StrideLog.w(TAG, "connect failed", e)
                state.update { it.copy(error = ConnectError.SaveFailed) }
            } finally {
                state.update { it.copy(connecting = false) }
            }
        }
    }

    private fun disconnect() {
        val deleteData = state.value.deleteSyncedData
        state.update { it.copy(disconnecting = true, error = null) }
        viewModelScope.launch {
            try {
                sync.cancelAll()
                secrets.clearGarminKey()
                garmin.clearToken()
                prefs.setGarminKeyRejected(false)
                if (deleteData) eraser.clearSyncedData()
                state.update { it.copy(showDisconnect = false, deleteSyncedData = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                StrideLog.w(TAG, "disconnect failed", e)
                state.update { it.copy(showDisconnect = false, error = ConnectError.SaveFailed) }
            } finally {
                state.update { it.copy(disconnecting = false) }
            }
        }
    }

    private companion object {
        const val TAG = "Connect"
    }
}

fun GarminError.toConnectError(): ConnectError = when (this) {
    GarminError.KeyMissing, GarminError.KeyInvalid -> ConnectError.InvalidKey
    is GarminError.Network -> ConnectError.Network
    is GarminError.RateLimited -> ConnectError.Other(UiText(R.string.connect_error_rate_limited))
    is GarminError.Server -> ConnectError.Other(UiText(R.string.connect_error_server, code))
    is GarminError.Http -> ConnectError.Other(UiText(R.string.connect_error_http, code))
    is GarminError.Parse -> ConnectError.Other(UiText(R.string.connect_error_parse))
}

private const val KEY_LIFETIME = "connect.lifetime"
private const val KEY_EXPIRY = "connect.expiry"
private const val KEY_SHOW_DISCONNECT = "connect.showDisconnect"
private const val KEY_DELETE_DATA = "connect.deleteSyncedData"
