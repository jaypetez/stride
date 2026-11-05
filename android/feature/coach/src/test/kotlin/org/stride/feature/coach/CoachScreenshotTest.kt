package org.stride.feature.coach

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.UnitSystem
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.captureScreenshot

/** Coach: setup (off by default), activity facts and an answer (MVP-019). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class CoachScreenshotTest {
    /** AUD-032: settings could not be read. */
    @Test fun error() = snap("error", CoachUiState(loading = false, loadFailed = true))

    /** AUD-037: a retired model shows a readable message. */
    @Test fun modelUnavailable() = snap(
        "model_unavailable",
        CoachUiState(loading = false, enabled = true, error = CoachLlmError.ModelUnavailable.message)
    )

    @Test fun setup() = snap("setup", CoachUiState(loading = false, caveatAccepted = true, keyInput = "sk-ant-xxxx"))

    @Test
    fun activity() = snap(
        "activity",
        CoachUiState(
            loading = false,
            enabled = true,
            activityId = 42,
            subject = CoachSubject.ACTIVITY,
            facts = buildActivityFacts(Samples.detail, UnitSystem.METRIC, ZoneOffset.UTC)
        )
    )

    @Test
    fun answer() = snap(
        "answer",
        CoachUiState(
            loading = false,
            enabled = true,
            facts = buildTrendFacts(Samples.today, 48.27, UnitSystem.METRIC),
            answer = CheckedReply(
                text = "Your fitness (CTL) is 52.4 and fatigue is 61.0, so form sits at -8.7: neutral.\n" +
                    "This week you ran 32.4 km over 4 runs, more than last week's 28.1 km.",
                removed = listOf("Aim for 70 km next week."),
                rejected = false
            )
        ),
        dark = true
    )

    private fun snap(name: String, state: CoachUiState, dark: Boolean = false) =
        captureScreenshot("src/test/screenshots/coach_$name.png", accessibilityChecks = !dark) {
            StrideTheme(darkTheme = dark) { CoachScreen(state = state, onEvent = {}, onBack = {}) }
        }
}
