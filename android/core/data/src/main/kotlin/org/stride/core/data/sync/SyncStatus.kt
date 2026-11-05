package org.stride.core.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sync progress for the UI (MVP-008). */
sealed interface SyncProgress {
    data object Idle : SyncProgress

    data class Running(val phase: String, val done: Int, val total: Int) : SyncProgress {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Succeeded(val atEpochSec: Long, val newActivities: Int) : SyncProgress

    data class Failed(val reason: SyncFailure) : SyncProgress
}

enum class SyncFailure(val message: String) {
    KEY_MISSING("Connect Garmin to sync your runs."),
    KEY_INVALID("Garmin rejected your API key. It may have expired — reconnect with a new key."),
    OFFLINE("No connection to Garmin. Sync will retry automatically."),
    RATE_LIMITED("Garmin is busy. Sync will retry shortly."),
    SERVER("Garmin had a problem. Sync will retry shortly."),
    UNEXPECTED("Sync failed unexpectedly. Try again later.")
}

@Singleton
class SyncStatus @Inject constructor() {
    private val state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = state.asStateFlow()

    fun update(progress: SyncProgress) {
        state.value = progress
    }
}
