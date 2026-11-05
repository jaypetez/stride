package org.stride.core.data.sync

import androidx.annotation.StringRes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.stride.core.data.R

/** Sync progress for the UI (MVP-008). */
sealed interface SyncProgress {
    data object Idle : SyncProgress

    data class Running(val phase: SyncPhase, val done: Int, val total: Int) : SyncProgress {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Succeeded(val atEpochSec: Long, val newActivities: Int) : SyncProgress

    data class Failed(val reason: SyncFailure) : SyncProgress
}

/** The step a running sync is on; [labelRes] is shown on Today and in Settings. */
enum class SyncPhase(@StringRes val labelRes: Int) {
    CHECKING(R.string.sync_phase_checking),
    IMPORTING(R.string.sync_phase_importing),
    DETAILS(R.string.sync_phase_details),
    COMPUTING(R.string.sync_phase_computing)
}

/** Why a sync stopped. Persisted by name (UserPreferences.lastSyncFailure) so it survives process death (AUD-032). */
enum class SyncFailure(@StringRes val messageRes: Int) {
    KEY_MISSING(R.string.sync_failure_key_missing),
    KEY_INVALID(R.string.sync_failure_key_invalid),
    OFFLINE(R.string.sync_failure_offline),
    RATE_LIMITED(R.string.sync_failure_rate_limited),
    SERVER(R.string.sync_failure_server),
    UNEXPECTED(R.string.sync_failure_unexpected);

    companion object {
        /** The persisted name back to a failure; unknown names (older builds) read as null. */
        fun fromName(name: String?): SyncFailure? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What screens should show for sync: live progress, or — when nothing is running
 * in this process — the last failure persisted across process death.
 */
fun SyncProgress.orLastFailure(lastFailure: String?): SyncProgress =
    if (this == SyncProgress.Idle) SyncFailure.fromName(lastFailure)?.let(SyncProgress::Failed) ?: this else this

@Singleton
class SyncStatus @Inject constructor() {
    private val state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = state.asStateFlow()

    fun update(progress: SyncProgress) {
        state.value = progress
    }
}
