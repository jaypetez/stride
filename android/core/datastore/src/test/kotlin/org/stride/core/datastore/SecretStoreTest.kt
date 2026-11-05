package org.stride.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.garmin.AccessToken

/**
 * Round-trip, clear and expiry-flow tests (MVP-004). Robolectric has no Android
 * Keystore, so the AEAD here is a Tink AES-GCM keyset held in memory; production
 * wraps the same AEAD with a Keystore master key ([KeystoreAead]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun testAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun TestScope.store(
        file: java.io.File = tmp.newFile("secrets.preferences_pb").also {
            it.delete()
        }
    ): Pair<SecretStore, java.io.File> {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        return SecretStore(ds, AeadSecretCipher(testAead())) to file
    }

    @Test
    fun keyRoundTripsAndIsEncryptedAtRest() = runTest(UnconfinedTestDispatcher()) {
        val (store, file) = store()
        store.saveGarminKey("  gpk-live-ABCDEF123456  ", LocalDate.parse("2027-11-01"))
        assertEquals("gpk-live-ABCDEF123456", store.apiKey())
        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("gpk-live-ABCDEF123456"), "key must not be stored in plaintext")
    }

    @Test
    fun tokenRoundTripsAndNewKeyDropsToken() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = store()
        store.saveGarminKey("k1", null)
        store.put(AccessToken("tok.en.value", 1_800_000_000))
        assertEquals(AccessToken("tok.en.value", 1_800_000_000), store.get())
        store.saveGarminKey("k2", null)
        assertNull(store.get())
    }

    @Test
    fun clearingKeyAlsoClearsCachedToken() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = store()
        store.saveGarminKey("k1", LocalDate.parse("2027-01-01"))
        store.put(AccessToken("tok", 1))
        store.clearGarminKey()
        assertNull(store.apiKey())
        assertNull(store.get())
    }

    @Test
    fun expiryIsExposedAsFlow() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = store()
        store.garminKeyExpiry.test {
            assertNull(awaitItem())
            store.saveGarminKey("k", LocalDate.parse("2027-02-03"))
            assertEquals(LocalDate.parse("2027-02-03"), awaitItem())
            store.clearGarminKey()
            assertNull(awaitItem())
        }
    }

    @Test
    fun undecryptableValuesReadAsAbsent() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("shared.preferences_pb").also { it.delete() }
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        SecretStore(ds, AeadSecretCipher(testAead())).saveGarminKey("k", null)
        // A different keyset (e.g. Keystore key lost after restore) cannot decrypt.
        val other = SecretStore(ds, AeadSecretCipher(testAead()))
        assertNull(other.apiKey())
    }

    @Test
    fun anthropicKeyIsSeparateAndWipeClearsEverything() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = store()
        store.saveGarminKey("g", null)
        store.saveAnthropicKey("sk-ant-test")
        assertEquals("sk-ant-test", store.anthropicKey())
        store.wipe()
        assertNull(store.apiKey())
        assertNull(store.anthropicKey())
    }
}
