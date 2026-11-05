package org.stride.feature.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults

/** Connect Garmin: empty, error, connected, expiring and rejected (MVP-005). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class ConnectScreenshotTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val base = ConnectUiState(loading = false, today = today, expiry = today.plusDays(90))

    @Test fun empty() = snap("empty", base)

    @Test fun error() = snap("error", base.copy(keyInput = "not-a-key", error = ConnectError.InvalidKey))

    @Test fun networkError() = snap("network_error", base.copy(keyInput = "key", error = ConnectError.Network), dark = true)

    @Test fun connected() = snap("connected", base.copy(connected = true, keyExpiry = today.plusDays(120)))

    @Test fun expiring() = snap("expiring", base.copy(connected = true, keyExpiry = today.plusDays(9)))

    @Test fun rejected() = snap("rejected", base.copy(connected = true, keyExpiry = today.plusDays(60), keyRejected = true))

    @Test fun disconnectDialog() = snap("disconnect", base.copy(connected = true, keyExpiry = today.plusDays(120), showDisconnect = true))

    private fun snap(name: String, state: ConnectUiState, dark: Boolean = false) =
        captureRoboImage("src/test/screenshots/connect_$name.png") {
            StrideTheme(darkTheme = dark) { ConnectScreen(state = state, onEvent = {}, onOpenGarmin = {}, onBack = {}) }
        }
}
