package org.stride.core.datastore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.stride.core.common.ApplicationScope
import org.stride.core.common.CoroutineScopesModule
import org.stride.core.garmin.AccessToken

/**
 * SecretStore on a real device (AUD-039): the production Hilt graph, the real
 * DataStore file and the Tink keyset wrapped by the Android Keystore, which
 * Robolectric cannot provide (SecretStoreTest fakes the AEAD).
 */
@HiltAndroidTest
@UninstallModules(CoroutineScopesModule::class)
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    /**
     * Each test gets a fresh Hilt graph, so a fresh DataStore on the same file. Owning
     * the application scope lets [tearDown] close the previous one first.
     */
    private val appJob = SupervisorJob()

    @BindValue
    @ApplicationScope
    @JvmField
    val appScope: CoroutineScope = CoroutineScope(appJob + Dispatchers.IO)

    @Inject
    lateinit var store: SecretStore

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { store.wipe() }
    }

    @After
    fun tearDown() = runBlocking {
        store.wipe()
        appJob.cancelAndJoin()
    }

    @Test
    fun garminKeyAndTokenRoundTripAndAreEncryptedAtRest() = runTest {
        store.saveGarminKey("  gpk-device-ABCDEF123456  ", LocalDate.parse("2027-11-01"))
        store.put(AccessToken("device.token.value", 1_900_000_000))

        assertEquals("gpk-device-ABCDEF123456", store.apiKey())
        assertEquals(AccessToken("device.token.value", 1_900_000_000), store.get())
        val onDisk = context.preferencesDataStoreFile(SecretStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("gpk-device-ABCDEF123456"), "the key must not be stored in plaintext")
        assertFalse(onDisk.contains("device.token.value"), "the token must not be stored in plaintext")
    }

    @Test
    fun aKeystoreWrappedKeysetReloadedFromDiskDecryptsEarlierCiphertext() {
        // A second AEAD loads the keyset from shared preferences and unwraps it with the
        // Keystore master key, as after a process restart.
        val first = AeadSecretCipher(KeystoreAead.create(context))
        val ciphertext = first.encrypt("sk-ant-device", "anthropic_api_key")
        val second = AeadSecretCipher(KeystoreAead.create(context))
        assertEquals("sk-ant-device", second.decrypt(ciphertext, "anthropic_api_key"))
    }

    @Test
    fun wipeRemovesEverySecret() = runTest {
        store.saveGarminKey("g", null)
        store.saveAnthropicKey("sk-ant-device")
        assertEquals("sk-ant-device", store.anthropicKey())
        store.wipe()
        assertNull(store.apiKey())
        assertNull(store.anthropicKey())
        assertNull(store.get())
    }
}
