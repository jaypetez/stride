package org.stride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.stride.core.garmin.AccessToken
import org.stride.core.garmin.GarminKeyProvider
import org.stride.core.garmin.TokenCache

/** What the UI may know about the Garmin connection — never the key itself. */
data class GarminConnection(val connected: Boolean, val keyExpiry: LocalDate?)

/**
 * Stores the Garmin Personal API key, its user-entered expiry and the cached
 * access token, encrypted at rest (MVP-004). Also stores the optional Anthropic
 * key for the BYOK coach (MVP-019). Nothing here is ever logged.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher
) : GarminKeyProvider,
    TokenCache {
    val garminConnection: Flow<GarminConnection> = dataStore.data.map { prefs ->
        GarminConnection(
            connected = prefs[GARMIN_KEY] != null,
            keyExpiry = prefs[GARMIN_KEY_EXPIRY]?.let(LocalDate::ofEpochDay)
        )
    }

    /** Emits the key expiry so the UI can warn before it lapses. */
    val garminKeyExpiry: Flow<LocalDate?> = garminConnection.map { it.keyExpiry }

    val hasAnthropicKey: Flow<Boolean> = dataStore.data.map { it[ANTHROPIC_KEY] != null }

    suspend fun saveGarminKey(key: String, expiry: LocalDate?) {
        val encrypted = cipher.encrypt(key.trim(), GARMIN_KEY.name)
        dataStore.edit { prefs ->
            prefs[GARMIN_KEY] = encrypted
            if (expiry != null) prefs[GARMIN_KEY_EXPIRY] = expiry.toEpochDay() else prefs.remove(GARMIN_KEY_EXPIRY)
            // A new key invalidates any token minted with the old one.
            prefs.remove(TOKEN)
            prefs.remove(TOKEN_EXPIRY)
        }
    }

    /** Clears the key, its expiry and the cached token together. */
    suspend fun clearGarminKey() {
        dataStore.edit { prefs ->
            prefs.remove(GARMIN_KEY)
            prefs.remove(GARMIN_KEY_EXPIRY)
            prefs.remove(TOKEN)
            prefs.remove(TOKEN_EXPIRY)
        }
    }

    override suspend fun apiKey(): String? = read(GARMIN_KEY)

    override suspend fun get(): AccessToken? {
        val prefs = dataStore.data.first()
        val value = prefs[TOKEN]?.let { decryptOrNull(it, TOKEN.name) } ?: return null
        val exp = prefs[TOKEN_EXPIRY] ?: return null
        return AccessToken(value, exp)
    }

    override suspend fun put(token: AccessToken) {
        val encrypted = cipher.encrypt(token.value, TOKEN.name)
        dataStore.edit { prefs ->
            prefs[TOKEN] = encrypted
            prefs[TOKEN_EXPIRY] = token.expiresAtEpochSec
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN)
            prefs.remove(TOKEN_EXPIRY)
        }
    }

    suspend fun saveAnthropicKey(key: String) {
        val encrypted = cipher.encrypt(key.trim(), ANTHROPIC_KEY.name)
        dataStore.edit { it[ANTHROPIC_KEY] = encrypted }
    }

    suspend fun anthropicKey(): String? = read(ANTHROPIC_KEY)

    suspend fun clearAnthropicKey() {
        dataStore.edit { it.remove(ANTHROPIC_KEY) }
    }

    /** Removes every secret (Settings → Delete all data). */
    suspend fun wipe() {
        dataStore.edit { it.clear() }
    }

    private suspend fun read(key: Preferences.Key<String>): String? =
        dataStore.data.first()[key]?.let { decryptOrNull(it, key.name) }

    /** A ciphertext that no longer decrypts (e.g. Keystore key lost) reads as absent. */
    private fun decryptOrNull(ciphertext: String, ad: String): String? = try {
        cipher.decrypt(ciphertext, ad)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** File name `datastore/stride_secrets.preferences_pb` — excluded from backup. */
        const val FILE_NAME = "stride_secrets"
        private val GARMIN_KEY = stringPreferencesKey("garmin_api_key")
        private val GARMIN_KEY_EXPIRY = longPreferencesKey("garmin_api_key_expiry_epoch_day")
        private val TOKEN = stringPreferencesKey("garmin_access_token")
        private val TOKEN_EXPIRY = longPreferencesKey("garmin_access_token_exp")
        private val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")
    }
}
