package org.stride.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
import java.security.ProviderException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.model.AthleteProfile

/** AUD-027: Keystore/Tink and DataStore corruption are recovered from, never crash. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun TestScope.dataStore(name: String) =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "$name.preferences_pb") }

    /** Like the OEM bug in tink#535: the first keyset load throws, the reset one works. */
    private class FlakyKeystore(private val failures: Int, private val fresh: () -> Aead) {
        var attempts = 0
        var resets = 0

        fun cipher() = RecoveringSecretCipher(
            createAead = {
                attempts++
                if (attempts <= failures) throw ProviderException("Keystore operation failed")
                fresh()
            },
            resetKeyset = { resets++ }
        )
    }

    @Test
    fun keysetFailureAtStartupResetsAndAsksToReenter() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore("secrets")
        // Secrets saved under the keyset that the Keystore later fails to unwrap.
        SecretStore(ds, AeadSecretCipher(newAead())).apply {
            saveGarminKey("gpk-old", null)
            saveAnthropicKey("sk-ant-old")
        }

        val keystore = FlakyKeystore(failures = 1, fresh = ::newAead)
        // Building the store (Hilt injection at launch) must not touch the Keystore, let alone crash.
        val store = SecretStore(ds, keystore.cipher())
        assertEquals(0, keystore.attempts)

        val connection = store.garminConnection.first()
        assertFalse(connection.connected, "a key that no longer decrypts is not connected")
        assertTrue(connection.needsReentry)
        assertFalse(store.hasAnthropicKey.first())
        assertEquals(1, keystore.resets)

        // Reading drops the dead ciphertexts but keeps asking for re-entry.
        assertNull(store.apiKey())
        assertNull(store.anthropicKey())
        assertTrue(store.garminConnection.first().needsReentry)

        // Re-entering the key works with the fresh keyset and clears the prompt.
        store.saveGarminKey("gpk-new", null)
        assertEquals("gpk-new", store.apiKey())
        assertEquals(GarminConnectionState(connected = true, needsReentry = false), store.garminConnection.first().state())
    }

    @Test
    fun keystoreStillBrokenAfterResetKeepsSecretsAndNeverCrashes() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore("secrets2")
        SecretStore(ds, AeadSecretCipher(newAead())).saveGarminKey("gpk-old", null)
        val keystore = FlakyKeystore(failures = Int.MAX_VALUE, fresh = ::newAead)
        val store = SecretStore(ds, keystore.cipher())

        assertEquals(GarminConnectionState(connected = false, needsReentry = true), store.garminConnection.first().state())
        assertNull(store.apiKey())
        // Transient: the ciphertext is kept for a later launch rather than deleted.
        assertTrue(store.garminConnection.first().needsReentry)
        assertFailsWith<KeystoreUnavailableException> { store.saveGarminKey("gpk-new", null) }
    }

    @Test
    fun connectedIsFalseWhenStoredKeyCannotBeDecrypted() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore("secrets3")
        SecretStore(ds, AeadSecretCipher(newAead())).saveGarminKey("k", null)
        val other = SecretStore(ds, AeadSecretCipher(newAead()))
        assertEquals(GarminConnectionState(connected = false, needsReentry = true), other.garminConnection.first().state())
        other.clearGarminKey()
        assertEquals(GarminConnectionState(connected = false, needsReentry = false), other.garminConnection.first().state())
    }

    @Test
    fun corruptPreferencesFileIsReplacedWithDefaultsAndLogged() = runTest(UnconfinedTestDispatcher()) {
        val file = File(tmp.root, "stride_prefs.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x12, 0x00, 0x55)) }
        val logged = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged += record.message.orEmpty()
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger("Stride")
        logger.addHandler(handler)
        try {
            val prefs = UserPreferences(resilientPreferencesDataStore(backgroundScope, "stride_prefs") { file })
            assertEquals(UserSettings(), prefs.current())
            // The store is usable again afterwards.
            prefs.completeOnboarding(AthleteProfile(name = "Ada"), parqFlagged = false)
            assertTrue(prefs.current().onboardingComplete)
            assertTrue(logged.any { "stride_prefs was corrupt" in it }, "corruption must be logged: $logged")
        } finally {
            logger.removeHandler(handler)
        }
    }

    private data class GarminConnectionState(val connected: Boolean, val needsReentry: Boolean)

    private fun GarminConnection.state() = GarminConnectionState(connected, needsReentry)
}
