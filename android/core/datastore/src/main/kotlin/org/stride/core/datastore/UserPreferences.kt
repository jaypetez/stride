package org.stride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.stride.core.model.AthleteProfile
import org.stride.core.model.UnitSystem

/** Non-secret app state (onboarding, profile, sync settings). Everything stays on the device. */
data class UserSettings(
    val onboardingComplete: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    /** True when any PAR-Q answer was "yes" — the app shows see-a-professional guidance. */
    val parqFlagged: Boolean = false,
    val profile: AthleteProfile = AthleteProfile(),
    val demoMode: Boolean = false,
    val syncWindowDays: Int = DEFAULT_SYNC_WINDOW_DAYS,
    val dynamicColor: Boolean = false,
    val coachEnabled: Boolean = false,
    val lastSyncEpochSec: Long? = null,
    /** Set when Garmin rejected the key during sync; the Connect screen shows it (MVP-008). */
    val garminKeyRejected: Boolean = false
) {
    val unitSystem: UnitSystem get() = profile.unitSystem

    companion object {
        const val DEFAULT_SYNC_WINDOW_DAYS = 180
    }
}

class UserPreferences(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<UserSettings> = dataStore.data.map { p ->
        UserSettings(
            onboardingComplete = p[ONBOARDED] ?: false,
            disclaimerAccepted = p[DISCLAIMER] ?: false,
            parqFlagged = p[PARQ_FLAGGED] ?: false,
            profile = p[PROFILE]?.let { runCatching { json.decodeFromString<AthleteProfile>(it) }.getOrNull() } ?: AthleteProfile(),
            demoMode = p[DEMO] ?: false,
            syncWindowDays = p[SYNC_WINDOW] ?: UserSettings.DEFAULT_SYNC_WINDOW_DAYS,
            dynamicColor = p[DYNAMIC_COLOR] ?: false,
            coachEnabled = p[COACH] ?: false,
            lastSyncEpochSec = p[LAST_SYNC],
            garminKeyRejected = p[KEY_REJECTED] ?: false
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun completeOnboarding(profile: AthleteProfile, parqFlagged: Boolean) {
        dataStore.edit {
            it[PROFILE] = json.encodeToString(AthleteProfile.serializer(), profile)
            it[PARQ_FLAGGED] = parqFlagged
            it[DISCLAIMER] = true
            it[ONBOARDED] = true
        }
    }

    suspend fun updateProfile(profile: AthleteProfile) {
        dataStore.edit { it[PROFILE] = json.encodeToString(AthleteProfile.serializer(), profile) }
    }

    suspend fun setUnitSystem(units: UnitSystem) {
        updateProfile(current().profile.copy(unitSystem = units))
    }

    suspend fun setDemoMode(enabled: Boolean) {
        dataStore.edit { it[DEMO] = enabled }
    }

    suspend fun setSyncWindowDays(days: Int) {
        require(days in 28..730) { "sync window must be 28..730 days" }
        dataStore.edit { it[SYNC_WINDOW] = days }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setCoachEnabled(enabled: Boolean) {
        dataStore.edit { it[COACH] = enabled }
    }

    suspend fun setLastSync(epochSec: Long) {
        dataStore.edit { it[LAST_SYNC] = epochSec }
    }

    suspend fun setGarminKeyRejected(rejected: Boolean) {
        dataStore.edit { it[KEY_REJECTED] = rejected }
    }

    /** Settings → Delete all data. */
    suspend fun wipe() {
        dataStore.edit { it.clear() }
    }

    companion object {
        const val FILE_NAME = "stride_prefs"
        private val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        private val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        private val PARQ_FLAGGED = booleanPreferencesKey("parq_flagged")
        private val PROFILE = stringPreferencesKey("profile_json")
        private val DEMO = booleanPreferencesKey("demo_mode")
        private val SYNC_WINDOW = intPreferencesKey("sync_window_days")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val COACH = booleanPreferencesKey("coach_enabled")
        private val LAST_SYNC = longPreferencesKey("last_sync_epoch_sec")
        private val KEY_REJECTED = booleanPreferencesKey("garmin_key_rejected")
    }
}
