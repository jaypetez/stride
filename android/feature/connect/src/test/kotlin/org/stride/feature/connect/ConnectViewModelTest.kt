package org.stride.feature.connect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stride.core.common.StrideResult
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

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeStrideClock() // 2026-11-01
    private val today = LocalDate.of(2026, 11, 1)

    private inner class Env(scope: TestScope) {
        val garmin = FakeGarminClient()
        val sync = FakeSync()
        val eraser = FakeEraser()
        val secrets =
            SecretStore(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "s.preferences_pb") }, TestCipher)
        val prefs = UserPreferences(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "p.preferences_pb") })

        fun vm() = ConnectViewModel(garmin, secrets, prefs, sync, eraser, clock)
    }

    @Test
    fun startsDisconnectedWithDefaultExpiry() = runTest(main.testDispatcher) {
        val env = Env(this)
        val state = env.vm().uiState.first { !it.loading }
        assertFalse(state.connected)
        assertTrue(state.needsNewKey)
        assertEquals(today.plusDays(90), state.expiry)
    }

    @Test
    fun lifetimeSetsTheDefaultExpiry() = runTest(main.testDispatcher) {
        val vm = Env(this).vm()
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.YEAR_1))
        assertEquals(today.plusYears(1), vm.uiState.value.expiry)
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.DAYS_30))
        assertEquals(today.plusDays(30), vm.uiState.value.expiry)
        vm.onEvent(ConnectEvent.ExpiryPicked(today.plusDays(12)))
        assertEquals(today.plusDays(12), vm.uiState.value.expiry)
    }

    @Test
    fun emptyKeyIsRejectedWithoutCallingGarmin() = runTest(main.testDispatcher) {
        val env = Env(this)
        val vm = env.vm()
        vm.onEvent(ConnectEvent.Connect)
        assertEquals(ConnectError.EmptyKey, vm.uiState.value.error)
        assertTrue(env.garmin.exchanged.isEmpty())
    }

    @Test
    fun invalidKeyShowsErrorAndSavesNothing() = runTest(main.testDispatcher) {
        val env = Env(this)
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
        val env = Env(this)
        env.garmin.result = StrideResult.Err(GarminError.Network("UnknownHostException"))
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("key"))
        vm.onEvent(ConnectEvent.Connect)
        assertEquals(ConnectError.Network, vm.uiState.value.error)
        assertNull(env.secrets.apiKey())
    }

    @Test
    fun validKeyIsSavedAndSyncStarts() = runTest(main.testDispatcher) {
        val env = Env(this)
        env.prefs.setGarminKeyRejected(true)
        val vm = env.vm()
        vm.onEvent(ConnectEvent.KeyChanged("good-key"))
        vm.onEvent(ConnectEvent.LifetimeChosen(KeyLifetime.DAYS_180))
        vm.onEvent(ConnectEvent.Connect)
        val state = vm.uiState.first { it.connected }
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
        val env = Env(this)
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
        val env = Env(this)
        env.secrets.saveGarminKey("k", today.plusDays(100))
        env.prefs.setGarminKeyRejected(true)
        val state = env.vm().uiState.first { it.connected }
        assertTrue(state.keyRejected)
        assertTrue(state.needsNewKey)
    }

    @Test
    fun disconnectRemovesKeyAndOptionallyData() = runTest(main.testDispatcher) {
        val env = Env(this)
        env.secrets.saveGarminKey("k", today.plusDays(100))
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        assertTrue(vm.uiState.value.showDisconnect)
        vm.onEvent(ConnectEvent.DeleteSyncedDataChanged(true))
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        val state = vm.uiState.first { !it.connected }
        assertFalse(state.showDisconnect)
        assertNull(env.secrets.apiKey())
        assertEquals(1, env.sync.cancelCalls)
        assertEquals(1, env.garmin.tokenCleared)
        assertEquals(1, env.eraser.cleared)
    }

    @Test
    fun disconnectKeepsDataByDefault() = runTest(main.testDispatcher) {
        val env = Env(this)
        env.secrets.saveGarminKey("k", null)
        val vm = env.vm()
        vm.uiState.first { it.connected }
        vm.onEvent(ConnectEvent.DisconnectClicked)
        vm.onEvent(ConnectEvent.DisconnectConfirmed)
        vm.uiState.first { !it.connected }
        assertEquals(0, env.eraser.cleared)
    }
}
