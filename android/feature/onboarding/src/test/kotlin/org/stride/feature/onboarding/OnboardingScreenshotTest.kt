package org.stride.feature.onboarding

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.Sex
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.UiText
import org.stride.core.ui.profile.ProfileField
import org.stride.core.ui.profile.ProfileFormState
import org.stride.core.ui.profile.RaceDistance

/** One screenshot per onboarding step (MVP-013). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class OnboardingScreenshotTest {
    private val profile = ProfileFormState(
        name = "Ada",
        birthYear = "1990",
        sex = Sex.FEMALE,
        maxHr = "188",
        raceDistance = RaceDistance.HALF,
        raceDate = LocalDate.of(2027, 3, 14)
    )

    @Test fun welcome() = snap("welcome", OnboardingUiState())

    @Test fun profile() = snap("profile", OnboardingUiState(step = OnboardingStep.PROFILE, profile = profile))

    @Test
    fun profileErrors() = snap(
        "profile_errors",
        OnboardingUiState(
            step = OnboardingStep.PROFILE,
            profile = ProfileFormState(birthYear = "2024"),
            profileErrors = mapOf(
                ProfileField.NAME to UiText(CoreUiR.string.profile_error_name_empty),
                ProfileField.BIRTH_YEAR to UiText(CoreUiR.string.profile_error_birth_year_range, 1926, 2013)
            )
        )
    )

    @Test fun parq() = snap("parq", OnboardingUiState(step = OnboardingStep.PARQ, parqAnswers = List(PARQ_QUESTIONS.size) { false }))

    @Test
    fun parqFlagged() = snap(
        "parq_flagged",
        OnboardingUiState(
            step = OnboardingStep.PARQ,
            parqAnswers = List(PARQ_QUESTIONS.size) {
                it == 1
            }
        )
    )

    @Test fun disclaimer() = snap("disclaimer", OnboardingUiState(step = OnboardingStep.DISCLAIMER, disclaimerAccepted = true))

    @Test fun finish() = snap(
        "finish",
        OnboardingUiState(step = OnboardingStep.FINISH, profile = profile, disclaimerAccepted = true),
        dark = true
    )

    /** AUD-035: large text and right-to-left. */
    @Test fun parqFont200() = snap(
        "parq_font200",
        OnboardingUiState(step = OnboardingStep.PARQ, parqAnswers = List(PARQ_QUESTIONS.size) { false }),
        variant = ScreenshotVariant.FONT_200
    )

    @Test fun profileFont150() = snap(
        "profile_font150",
        OnboardingUiState(step = OnboardingStep.PROFILE, profile = profile),
        variant = ScreenshotVariant.FONT_150
    )

    @Test fun welcomeRtl() = snap("welcome_rtl", OnboardingUiState(), variant = ScreenshotVariant.RTL)

    private fun snap(
        name: String,
        state: OnboardingUiState,
        dark: Boolean = false,
        variant: ScreenshotVariant = ScreenshotVariant.DEFAULT
    ) = captureScreenshot("src/test/screenshots/onboarding_$name.png", variant = variant, accessibilityChecks = !dark) {
        StrideTheme(darkTheme = dark) { OnboardingScreen(state = state, onEvent = {}) }
    }
}
