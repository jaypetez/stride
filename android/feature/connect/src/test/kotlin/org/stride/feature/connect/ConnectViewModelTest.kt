package org.stride.feature.connect

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.stride.core.common.StrideResult
import org.stride.core.data.testing.InMemoryPreferences
import org.stride.core.data.testing.flakyPreferences
import org.stride.core.datastore.SecretStore
import org.stride.core.datastore.UserPreferences
import org.stride.core.garmin.GarminError
import org.stride.core.testing.FakeStrideClock
import org.stride.core.testing.MainDispatcherRule

/** Key validation, saving, expiry warnings and disconnect (MVP-005). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = FakeStrideClock() // 2026-11-01
    private val today = LocalDate.of(2026, 11, 1)

    private inner class Env {
        val garmin = FakeGarminClient()
        val sync = FakeSync()
        val eraser = FakeEraser()
        val secretsStore = InMemoryPreferences()
        val secrets = SecretStore(secretsStore, TestCipher)
        val prefs = UserPreferences(InMemoryPreferences())

        fun vm(saved: SavedStateHandle = SavedStateHandle()) = ConnectViewModel(garmin, secrets, prefs, sync, eraser, clock, saved)
    }

    /** AUD-032: unreadable settings show the error state; "Try again" recovers. */
    @Test
    fun loadErrorShowsErrorStateAndRetryRecovers() = runTest(main.testDispatcher) {
        val env = Env()
        val (flaky, _) = flakyPreferences(InMemoryPreferences())
        val vm = ConnectViewModel(env.garmin, env.secrets, flaky, env.sync, env.eraser, clock, SavedStateHandle())
        assertTrue(vm.uiState.first { !it.loading }.loadFailed)
        vm.onEvent(ConnectEvent.Retry)
        val state = vm.uiState.first { !it.loading }
        assertFalse(state.loadFailed)
        assertFalse(state.connected)
    }

    /** AUD-033: the disconnect dialog and form choices survive process death; the pasted key does not. */
    @Test
    fun disconnectDialogAndFormSurviveProcessDeath() = runTest(main.testDispatcher) {
        val env = Env()
        val saved = SavedStateHandle()
        val before = env.vm(saved)
        before.onEvent(ConnectEvent.KeyChanged("secret-key"))
        before.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.DAYS_180))
        before.onEvent(ConnectEvent.DisconnectClicked)
        before.onEvent(ConnectEvent.DeleteSyncedDataChanged(true))

        // Process death: only what was written to the SavedStateHandle comes back.
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        assertFalse(restoredHandle.keys().any { saved.get<Any>(it) == "secret-key" }, "the key is never saved to the bundle")
        val after = env.vm(restoredHandle).uiState.first { !it.loading }
        assertTrue(after.showDisconnect)
        assertTrue(after.deleteSyncedData)
        assertEquals(KeyLifetime.DAYS_180, after.lifetime)
        assertEquals(today.plusDays(180), after.expiry)
        assertEquals("", after.keyInput)
    }

    /** AUD-037: an expiry in the past is never stored. */
    @Test
    fun pastExpiryIsClampedToToday() = runTest(main.testDispatcher) {
        val vm = Env().vm()
        vm.onEvent(ConnectEvent.ExpiryPicked(today.minusDays(3)))
        assertEquals(today, vm.uiState.value.expiry)
        vm.onEvent(ConnectEvent.ExpiryPicked(today.plusDays(3)))
        assertEquals(today.plusDays(3), vm.uiState.value.expiry)
    }

    /** AUD-037: a date restored after process death on a later day is clamped to the new today. */
    @Test
    fun restoredPastExpiryIsClampedToToday() = runTest(main.testDispatcher) {
        val env = Env()
        val saved = SavedStateHandle()
        env.vm(saved).onEvent(ConnectEvent.ExpiryPicked(today.plusDays(2)))
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

        // The process comes back five days later: the picked date is now in the past.
        clock.instant = clock.instant.plus(Duration.ofDays(5))
        val later = today.plusDays(5)
        val vm = env.vm(restoredHandle)
        assertEquals(later, vm.uiState.value.expiry)

        vm.onEvent(ConnectEvent.KeyChanged("good-key"))
        vm.onEvent(ConnectEvent.Connect)
        assertEquals(later, vm.uiState.first { it.connected }.keyExpiry, "a past expiry is never saved")
    }

    @Test
    fun startsDisconnectedWithDefaultExpiry() = runTest(main.testDispatcher) {
        val env = Env()
        val state = env.vm().uiState.first { !it.loading }
        assertFalse(state.connected)
        assertTrue(state.needsNewKey)
        assertEquals(today.plusDays(90), state.expiry)
    }

    @Test
    fun lifetimeSetsTheDefaultExpiry() = runTest(main.testDispatcher) {
        val vm = Env().vm()
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.YEAR_1))
        assertEquals(today.plusYears(1), vm.uiState.value.expiry)
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.DAYS_30))
        assertEquals(today.plusDays(30), vm.uiState.value.expiry)
        vm.onEvent(ConnectEvent.ExpiryPicked(today.plusDays(12)))
        assertEquals(today.plusDays(12), vm.uiState.value.expiry)
    }

    @Test
    fun emptyKeyIsRejectedWithoutCallingGarmin() = runTest(main.testDispatcher) {
        val env = Env()
        val vm = env.vm()
        vm.onEvent(ConnectEvent.Connect)
        assertEquals(ConnectError.EmptyKey, vm.uiState.value.error)
        assertTrue(env.garmin.exchanged.isEmpty())
    }

    @Test
    fun invalidKeyShowsErrorAndSavesNothing() = runTest(main.testDispatcher) {
        val env = Env()
        env.garmin.result = StrideResult.Err(GarminError.KeyInvalid)
        val vm = env.vm()
        vm.uiState.test {
            awaitItem()
            vm.onEvent(ConnectEvent.KeyChanged("  bad-key  "))
            vm.onEvent(ConnectEvent.Connect)
            val failed = expectMostRecentItem()
            assertEquals(ConnectError.InvalidKey, failed.error)
            assertFalse(failed.connecting)
        }
        assertEquals(listOf<String?>("bad-key"), env.garmin.exchanged)
        assertNull(env.secrets.apiKey())
        assertEquals(0, env.sync.syncNowCalls)
    }

    @Test
    fun networkFailureIsDistinctFromInvalidKey() = runTest(main.testDispatcher) {
        val env = Env()
        env.garmin.result = StrideResult.Err(GarminError.Network("UnknownHostException"))
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("key"))
        vm.onEvent(ConnectEvent.Connect)
        assertEquals(ConnectError.Network, vm.uiState.value.error)
        assertNull(env.secrets.apiKey())
    }

    @Test
    fun validKeyIsSavedAndSyncStarts() = runTest(main.testDispatcher) {
        val env = Env()
        env.prefs.setGarminKeyRejected(true)
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("good-key"))
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.DAYS_180))
        vm.onEvent(ConnectEvent.Connect)
        val state = vm.uiState.first { it.connected && !it.connecting }
        assertEquals("good-key", env.secrets.apiKey())
        assertEquals(today.plusDays(180), state.keyExpiry)
        assertEquals("", state.keyInput, "the pasted key is dropped from UI state")
        assertFalse(state.keyRejected)
        assertFalse(state.needsNewKey)
        assertEquals(1, env.sync.syncNowCalls)
        assertEquals(1, env.sync.periodicCalls)
    }

    @Test
    fun expiryWithinFourteenDaysWarns() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", today.plusDays(10))
        val state = env.vm().uiState.first { it.connected }
        assertEquals(10L, state.daysLeft)
        assertTrue(state.expiringSoon)
        assertTrue(state.needsNewKey)

        env.secrets.saveGarminKey("k", today.plusDays(40))
        val later = env.vm().uiState.first { it.connected && it.keyExpiry == today.plusDays(40) }
        assertFalse(later.expiringSoon)
    }

    @Test
    fun rejectedKeyIsSurfaced() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", today.plusDays(100))
        env.prefs.setGarminKeyRejected(true)
        val state = env.vm().uiState.first { it.connected }
        assertTrue(state.keyRejected)
        assertTrue(state.needsNewKey)
    }

    @Test
    fun disconnectRemovesKeyAndOptionallyData() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", today.plusDays(100))
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        assertTrue(vm.uiState.value.showDisconnect)
        vm.onEvent(ConnectEvent.DeleteSyncedDataChanged(true))
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        val state = vm.uiState.first { !it.connected && !it.disconnecting }
        assertFalse(state.showDisconnect)
        assertNull(env.secrets.apiKey())
        assertEquals(1, env.sync.cancelCalls)
        assertEquals(1, env.garmin.tokenCleared)
        assertEquals(1, env.eraser.cleared)
    }

    /** AUD-032: disconnecting clears the persisted sync failure, since no sync will run to clear it. */
    @Test
    fun disconnectClearsThePersistedSyncFailure() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", today.plusDays(100))
        env.prefs.setGarminKeyRejected(true)
        env.prefs.setLastSyncFailure("KEY_INVALID")
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        vm.uiState.first { !it.connected && !it.disconnecting }
        assertNull(env.prefs.settings.first().lastSyncFailure)
        assertFalse(env.prefs.settings.first().garminKeyRejected)
    }

    /** AUD-032: connecting a new key clears the old key's persisted sync failure. */
    @Test
    fun connectClearsThePersistedSyncFailure() = runTest(main.testDispatcher) {
        val env = Env()
        env.prefs.setLastSyncFailure("KEY_INVALID")
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("good-key"))
        vm.onEvent(ConnectEvent.Connect)
        vm.uiState.first { it.connected }
        assertNull(env.prefs.settings.first().lastSyncFailure)
    }

    @Test
    fun disconnectKeepsDataByDefault() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", null)
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        vm.uiState.first { !it.connected && !it.disconnecting }
        assertEquals(0, env.eraser.cleared)
    }

    /** AUD-030: an unexpected failure while connecting shows an error and clears the spinner. */
    @Test
    fun connectFailureShowsErrorAndClearsSpinner() = runTest(main.testDispatcher) {
        val env = Env()
        env.garmin.throwOnExchange = IllegalStateException("boom")
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("key"))
        vm.onEvent(ConnectEvent.Connect)
        val state = vm.uiState.first { it.error != null }
        assertEquals(ConnectError.SaveFailed, state.error)
        assertFalse(vm.uiState.first { !it.connecting }.connecting)
        assertNull(env.secrets.apiKey())
    }

    /** AUD-030: a failed disconnect shows an error and clears the spinner. */
    @Test
    fun disconnectFailureShowsErrorAndClearsSpinner() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", null)
        env.eraser.throwOnClear = IllegalStateException("disk full")
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        vm.onEvent(ConnectEvent.DeleteSyncedDataChanged(true))
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        val state = vm.uiState.first { it.error != null && !it.disconnecting }
        assertEquals(ConnectError.SaveFailed, state.error)
        assertFalse(state.showDisconnect)
    }

    /** AUD-027: a stored key that no longer decrypts asks the user to re-enter it. */
    @Test
    fun undecryptableKeyAsksForReentry() = runTest(main.testDispatcher) {
        val env = Env()
        env.secrets.saveGarminKey("k", null)
        val broken = object : org.stride.core.datastore.SecretCipher {
            override fun encrypt(plaintext: String, associatedData: String) = TestCipher.encrypt(plaintext, associatedData)

            override fun decrypt(ciphertext: String, associatedData: String): String = throw java.security.GeneralSecurityException("tag")
        }
        val vm = ConnectViewModel(env.garmin, SecretStore(env.secretsStore, broken), env.prefs, env.sync, env.eraser, clock, SavedStateHandle())
        val state = vm.uiState.first { !it.loading }
        assertFalse(state.connected)
        assertTrue(state.keyLost)
        assertTrue(state.needsNewKey)
    }
}
