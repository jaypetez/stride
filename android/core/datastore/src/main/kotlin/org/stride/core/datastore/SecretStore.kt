package org.stride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.stride.core.garmin.AccessToken
import org.stride.core.garmin.GarminKeyProvider
import org.stride.core.garmin.TokenCache

/** What the UI may know about the Garmin connection — never the key itself. */
data class GarminConnection(
    /** A key is stored *and* decrypts (AUD-027): a key the Keystore lost is not "connected". */
    val connected: Boolean,
    val keyExpiry: LocalDate?,
    /** Saved secrets could not be unlocked (Keystore reset or lost key); the user must re-enter them. */
    val needsReentry: Boolean = false
)

/** Decryption outcome for one stored secret. */
private sealed interface Unsealed {
    data class Value(val plaintext: String) : Unsealed

    /** The ciphertext can never decrypt again (wrong or reset keyset, tampered bytes). */
    data object Lost : Unsealed

    /** The Keystore is unavailable right now; the ciphertext is kept for a later launch. */
    data object Unavailable : Unsealed
}

/**
 * Stores the Garmin Personal API key, its user-entered expiry and the cached
 * access token, encrypted at rest (MVP-004). Also stores the optional Anthropic
 * key for the BYOK coach (MVP-019). Nothing here is ever logged.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    /** Where to decrypt: the first use loads the Keystore keyset, so production passes the IO dispatcher. */
    private val io: CoroutineContext = EmptyCoroutineContext
) : GarminKeyProvider,
    TokenCache {
    val garminConnection: Flow<GarminConnection> = dataStore.data.map { prefs ->
        val key = prefs[GARMIN_KEY]?.let { unseal(it, GARMIN_KEY.name) }
        GarminConnection(
            connected = key is Unsealed.Value,
            keyExpiry = prefs[GARMIN_KEY_EXPIRY]?.let(LocalDate::ofEpochDay),
            needsReentry = (key != null && key !is Unsealed.Value) || prefs[SECRETS_LOST] == true
        )
    }.distinctUntilChanged().flowOn(io)

    /** Emits the key expiry so the UI can warn before it lapses. */
    val garminKeyExpiry: Flow<LocalDate?> = garminConnection.map { it.keyExpiry }

    /** True only when the stored Anthropic key still decrypts. */
    val hasAnthropicKey: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[ANTHROPIC_KEY]?.let { unseal(it, ANTHROPIC_KEY.name) } is Unsealed.Value }
        .distinctUntilChanged()
        .flowOn(io)

    suspend fun saveGarminKey(key: String, expiry: LocalDate?) {
        val encrypted = withContext(io) { cipher.encrypt(key.trim(), GARMIN_KEY.name) }
        dataStore.edit { prefs ->
            prefs.remove(SECRETS_LOST)
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
            prefs.remove(SECRETS_LOST)
        }
    }

    override suspend fun apiKey(): String? = read(GARMIN_KEY)

    override suspend fun get(): AccessToken? {
        val value = read(TOKEN) ?: return null
        val exp = dataStore.data.first()[TOKEN_EXPIRY] ?: return null
        return AccessToken(value, exp)
    }

    override suspend fun put(token: AccessToken) {
        val encrypted = withContext(io) { cipher.encrypt(token.value, TOKEN.name) }
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
        val encrypted = withContext(io) { cipher.encrypt(key.trim(), ANTHROPIC_KEY.name) }
        dataStore.edit {
            it.remove(SECRETS_LOST)
            it[ANTHROPIC_KEY] = encrypted
        }
    }

    suspend fun anthropicKey(): String? = read(ANTHROPIC_KEY)

    suspend fun clearAnthropicKey() {
        dataStore.edit { it.remove(ANTHROPIC_KEY) }
    }

    /** Removes every secret (Settings → Delete all data). */
    suspend fun wipe() {
        dataStore.edit { it.clear() }
    }

    /**
     * A ciphertext that no longer decrypts reads as absent. One that can never decrypt
     * again (keyset reset, AUD-027) is deleted and [GarminConnection.needsReentry] is set,
     * so the Connect screen asks for the key again instead of failing every sync.
     */
    private suspend fun read(key: Preferences.Key<String>): String? {
        val ciphertext = dataStore.data.first()[key] ?: return null
        return when (val result = withContext(io) { unseal(ciphertext, key.name) }) {
            is Unsealed.Value -> result.plaintext

            Unsealed.Unavailable -> null

            Unsealed.Lost -> {
                dataStore.edit { prefs ->
                    // Only drop what failed; a concurrent save may already have replaced it.
                    if (prefs[key] == ciphertext) {
                        prefs.remove(key)
                        prefs[SECRETS_LOST] = true
                    }
                }
                null
            }
        }
    }

    private fun unseal(ciphertext: String, ad: String): Unsealed = try {
        Unsealed.Value(cipher.decrypt(ciphertext, ad))
    } catch (_: KeystoreUnavailableException) {
        Unsealed.Unavailable
    } catch (_: GeneralSecurityException) {
        Unsealed.Lost
    } catch (_: IllegalArgumentException) {
        Unsealed.Lost
    }

    companion object {
        /** File name `datastore/stride_secrets.preferences_pb` — excluded from backup. */
        const val FILE_NAME = "stride_secrets"
        private val GARMIN_KEY = stringPreferencesKey("garmin_api_key")
        private val GARMIN_KEY_EXPIRY = longPreferencesKey("garmin_api_key_expiry_epoch_day")
        private val TOKEN = stringPreferencesKey("garmin_access_token")
        private val TOKEN_EXPIRY = longPreferencesKey("garmin_access_token_exp")
        private val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")

        /** Set when undecryptable secrets were dropped; cleared when a key is saved again. */
        private val SECRETS_LOST = booleanPreferencesKey("secrets_lost")
    }
}
