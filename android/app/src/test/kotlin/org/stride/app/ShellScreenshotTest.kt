package org.stride.app

import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.testing.ScreenshotDefaults

/** The shell at compact (bar), medium and expanded (rail) widths (ANDF-010). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK])
class ShellScreenshotTest {
    private val installers: Set<EntryProviderInstaller> = setOf({
        entry<TodayRoute> { Text("Today") }
        entry<ActivitiesRoute> { Text("Runs") }
        entry<FitnessRoute> { Text("Fitness") }
        entry<PlanRoute> { Text("Plan") }
        entry<SettingsRoute> { Text("You") }
    })

    @Test
    @Config(qualifiers = ScreenshotDefaults.PHONE)
    fun compact() = snap("compact")

    @Test
    @Config(qualifiers = ScreenshotDefaults.MEDIUM)
    fun medium() = snap("medium")

    @Test
    @Config(qualifiers = ScreenshotDefaults.EXPANDED)
    fun expanded() = snap("expanded")

    private fun snap(name: String) = captureRoboImage("src/test/screenshots/shell_$name.png") {
        StrideTheme(darkTheme = false) { StrideApp(installers = installers, demoMode = name == "compact") }
    }
}
