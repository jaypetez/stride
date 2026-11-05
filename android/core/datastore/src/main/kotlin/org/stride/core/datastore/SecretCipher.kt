package org.stride.core.datastore

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import org.stride.core.common.StrideLog

/** Encrypts small secrets. Associated data binds each ciphertext to its preference key. */
interface SecretCipher {
    fun encrypt(plaintext: String, associatedData: String): String

    fun decrypt(ciphertext: String, associatedData: String): String
}

/** Tink AEAD wrapper: base64(ciphertext) in, plaintext out. */
class AeadSecretCipher(private val aead: Aead) : SecretCipher {
    override fun encrypt(plaintext: String, associatedData: String): String =
        Base64.getEncoder().encodeToString(aead.encrypt(plaintext.toByteArray(), associatedData.toByteArray()))

    override fun decrypt(ciphertext: String, associatedData: String): String =
        String(aead.decrypt(Base64.getDecoder().decode(ciphertext), associatedData.toByteArray()))
}

/**
 * The Android Keystore could not provide an AEAD, even after resetting the keyset.
 * Transient (another launch may succeed), so stored ciphertexts are kept.
 */
class KeystoreUnavailableException(cause: Throwable) : GeneralSecurityException("keystore unavailable", cause)

/**
 * A [SecretCipher] that builds its AEAD lazily, off the injection path, and recovers
 * from a broken keyset (AUD-027). Some OEM Keystores fail to unwrap a Tink keyset
 * (tink#535); building it eagerly in Hilt crashed every launch. Here a failed build
 * deletes the keyset (via [resetKeyset]) and retries once. Secrets encrypted with the
 * old keyset then no longer decrypt, and [SecretStore] asks the user to re-enter them.
 * If the retry fails too, every call throws [KeystoreUnavailableException].
 */
class RecoveringSecretCipher(
    private val createAead: () -> Aead,
    private val resetKeyset: () -> Unit
) : SecretCipher {
    private val lock = Any()
    private var cipher: AeadSecretCipher? = null

    override fun encrypt(plaintext: String, associatedData: String): String = cipher().encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: String, associatedData: String): String = cipher().decrypt(ciphertext, associatedData)

    private fun cipher(): AeadSecretCipher = synchronized(lock) {
        cipher ?: AeadSecretCipher(open()).also { cipher = it }
    }

    @Suppress("TooGenericExceptionCaught") // Keystore/Tink failures arrive as assorted checked and runtime exceptions
    private fun open(): Aead = try {
        createAead()
    } catch (first: Exception) {
        StrideLog.w(TAG, "keyset unusable; resetting it, stored secrets must be re-entered", first)
        try {
            resetKeyset()
            createAead()
        } catch (second: Exception) {
            StrideLog.w(TAG, "keystore unavailable after keyset reset", second)
            throw KeystoreUnavailableException(second)
        }
    }

    private companion object {
        const val TAG = "SecretCipher"
    }
}

/**
 * The production AEAD: an AES-256-GCM Tink keyset stored in the
 * `stride_tink_keyset` shared preferences, itself encrypted by a master key in
 * the Android Keystore. Both files are excluded from backup and device transfer
 * (data_extraction_rules.xml), and the Keystore key never leaves the device.
 */
object KeystoreAead {
    const val KEYSET_PREFS = "stride_tink_keyset"
    private const val KEYSET_NAME = "stride_secrets_keyset"
    private const val MASTER_KEY_ALIAS = "stride_master_key"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    fun create(context: Context): Aead {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Deletes the wrapped keyset and its Keystore master key so [create] starts fresh. */
    fun reset(context: Context) {
        context.deleteSharedPreferences(KEYSET_PREFS)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure { StrideLog.w("KeystoreAead", "could not delete the master key", it) }
    }

    /** The cipher the app uses: lazy and self-healing ([RecoveringSecretCipher]). */
    fun recoveringCipher(context: Context): SecretCipher =
        RecoveringSecretCipher(createAead = { create(context) }, resetKeyset = { reset(context) })
}

/** Defers building the real cipher (Keystore + Tink) until the first secret is read or written (AUD-036). */
class LazySecretCipher(factory: () -> SecretCipher) : SecretCipher {
    private val delegate by lazy(factory)

    override fun encrypt(plaintext: String, associatedData: String): String = delegate.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: String, associatedData: String): String = delegate.decrypt(ciphertext, associatedData)
}
