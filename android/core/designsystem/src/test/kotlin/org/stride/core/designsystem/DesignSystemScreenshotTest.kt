package org.stride.core.designsystem

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.component.ComponentShowcase
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.captureScreenshot

/** Every design-system component in light and dark (ANDF-002 / ANDF-008). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class DesignSystemScreenshotTest {
    @Test fun buttons() = snap("buttons") { ComponentShowcase.Buttons() }

    @Test fun cards() = snap("cards") { ComponentShowcase.Cards() }

    @Test fun states() = snap("states") { ComponentShowcase.States() }

    @Test fun chrome() = snap("chrome") { ComponentShowcase.Chrome() }

    private fun snap(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureScreenshot("src/test/screenshots/designsystem_${name}_$theme.png") {
                StrideTheme(darkTheme = dark) { content() }
            }
        }
    }
}
