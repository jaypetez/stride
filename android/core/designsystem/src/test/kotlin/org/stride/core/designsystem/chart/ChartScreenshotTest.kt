package org.stride.core.designsystem.chart

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults

/** The Canvas chart toolkit in light and dark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class ChartScreenshotTest {
    @Test fun pmc() = snap("pmc") { ChartShowcase.Pmc() }

    @Test fun acwr() = snap("acwr") { ChartShowcase.Acwr() }

    @Test fun pace() = snap("pace") { ChartShowcase.Pace() }

    @Test fun zones() = snap("zones") { ChartShowcase.Zones() }

    private fun snap(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureRoboImage("src/test/screenshots/chart_${name}_$theme.png") {
                StrideTheme(darkTheme = dark) { content() }
            }
        }
    }
}
