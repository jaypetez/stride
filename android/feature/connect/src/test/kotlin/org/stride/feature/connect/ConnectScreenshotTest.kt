package org.stride.feature.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot

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

    /** AUD-032: the connection state could not be read. */
    @Test fun loadError() = snap("load_error", base.copy(loadFailed = true))

    /** AUD-035: large text and right-to-left. */
    @Test fun emptyFont200() = snap("empty_font200", base, variant = ScreenshotVariant.FONT_200)

    @Test fun expiringRtl() = snap(
        "expiring_rtl",
        base.copy(connected = true, keyExpiry = today.plusDays(9)),
        variant = ScreenshotVariant.RTL
    )

    private fun snap(name: String, state: ConnectUiState, dark: Boolean = false, variant: ScreenshotVariant = ScreenshotVariant.DEFAULT) =
        captureScreenshot("src/test/screenshots/connect_$name.png", variant = variant, accessibilityChecks = !dark) {
            StrideTheme(darkTheme = dark) { ConnectScreen(state = state, onEvent = {}, onOpenGarmin = {}, onBack = {}) }
        }
}
