package org.stride.feature.onboarding

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.Sex
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.testing.ScreenshotDefaults
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
            profileErrors = mapOf(ProfileField.NAME to "Enter your name", ProfileField.BIRTH_YEAR to "Enter a year between 1926 and 2013")
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

    private fun snap(name: String, state: OnboardingUiState, dark: Boolean = false) =
        captureRoboImage("src/test/screenshots/onboarding_$name.png") {
            StrideTheme(darkTheme = dark) { OnboardingScreen(state = state, onEvent = {}) }
        }
}
