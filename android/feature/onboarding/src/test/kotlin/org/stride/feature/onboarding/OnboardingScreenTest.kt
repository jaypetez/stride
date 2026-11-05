package org.stride.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.testing.ScreenshotDefaults

/** Golden text: the PAR-Q questions and the disclaimer are shown verbatim from core:science (MVP-013). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun parqQuestionsAreShownVerbatim() {
        compose.setContent { StrideTheme { OnboardingScreen(OnboardingUiState(step = OnboardingStep.PARQ), onEvent = {}) } }
        assertEquals(7, PARQ_QUESTIONS.size)
        PARQ_QUESTIONS.forEach { question ->
            // Exact, whole-string match: any edit to the wording fails here.
            compose.onNodeWithText(question, substring = false).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun anyYesShowsProfessionalGuidance() {
        val answers = List(PARQ_QUESTIONS.size) { if (it == 0) true else false }
        compose.setContent {
            StrideTheme { OnboardingScreen(OnboardingUiState(step = OnboardingStep.PARQ, parqAnswers = answers), onEvent = {}) }
        }
        compose.onNodeWithText(PARQ_GUIDANCE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("You flagged: heart condition.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun disclaimerIsShownVerbatim() {
        compose.setContent { StrideTheme { OnboardingScreen(OnboardingUiState(step = OnboardingStep.DISCLAIMER), onEvent = {}) } }
        compose.onNodeWithText(DISCLAIMER, substring = false).assertIsDisplayed()
    }
}
