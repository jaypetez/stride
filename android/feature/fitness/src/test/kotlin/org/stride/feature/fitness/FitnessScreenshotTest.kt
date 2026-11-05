package org.stride.feature.fitness

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot
import org.stride.core.testing.variantPath

/** Fitness in demo mode (MVP-016), built from the real demo pipeline. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class FitnessScreenshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun demoState(range: FitnessRange): FitnessUiState {
        lateinit var state: FitnessUiState
        runTest {
            val fx = DemoDataFixture(this, tmp.root)
            fx.loadDemo()
            val insights = fx.insights.fitness()
            state = FitnessUiState(isLoading = false, range = range, window = pmcWindow(insights.pmc, range), insights = insights)
        }
        return state
    }

    private fun snap(name: String, state: FitnessUiState, variant: ScreenshotVariant = ScreenshotVariant.DEFAULT) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureScreenshot(
                variantPath("src/test/screenshots/fitness_$name", variant, theme),
                variant = variant,
                accessibilityChecks = !dark
            ) {
                StrideTheme(darkTheme = dark) { FitnessScreen(state = state, onSelectRange = {}) }
            }
        }
    }

    @Test
    fun demo() = snap("demo", demoState(FitnessRange.THREE_MONTHS))

    /** Full scroll height: zones, VDOT, predictions and zone tables. */
    @Test
    @Config(qualifiers = "en-rGB-w411dp-h2600dp-xxhdpi")
    fun demoFullSixMonths() = snap("demo_full_6m", demoState(FitnessRange.SIX_MONTHS))

    @Test
    fun empty() = snap("empty", FitnessUiState(isLoading = false))

    /** AUD-032: the insights could not be computed. */
    @Test
    fun error() = snap("error", FitnessUiState(isLoading = false, loadFailed = true))

    /** AUD-035: large text and right-to-left. */
    @Test
    fun demoFont150() = snap("demo", demoState(FitnessRange.THREE_MONTHS), ScreenshotVariant.FONT_150)

    @Test
    fun demoRtl() = snap("demo", demoState(FitnessRange.THREE_MONTHS), ScreenshotVariant.RTL)
}
