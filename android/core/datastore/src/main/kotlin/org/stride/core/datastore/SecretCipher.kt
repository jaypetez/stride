package org.stride.core.datastore

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.Base64

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
 * The production AEAD: an AES-256-GCM Tink keyset stored in the
 * `stride_tink_keyset` shared preferences, itself encrypted by a master key in
 * the Android Keystore. Both files are excluded from backup and device transfer
 * (data_extraction_rules.xml), and the Keystore key never leaves the device.
 */
object KeystoreAead {
    const val KEYSET_PREFS = "stride_tink_keyset"
    private const val KEYSET_NAME = "stride_secrets_keyset"
    private const val MASTER_KEY_URI = "android-keystore://stride_master_key"

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
}
