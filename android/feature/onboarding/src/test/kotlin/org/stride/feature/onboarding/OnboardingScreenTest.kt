package org.stride.feature.onboarding

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.ui.profile.ProfileFormState
import org.stride.core.ui.profile.RaceDistance

/** Golden text: the PAR-Q questions and the disclaimer are shown verbatim from core:science (MVP-013). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun accessibility() = compose.enableAccessibilityChecks()

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
        val guidance = ApplicationProvider.getApplicationContext<Context>().getString(R.string.onboarding_parq_guidance)
        compose.onNodeWithText(guidance).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("You flagged: heart condition.").performScrollTo().assertIsDisplayed()
    }

    /** AUD-035: TalkBack reads each answer together with its question, as a radio button. */
    @Test
    fun parqAnswersCarryTheirQuestion() {
        compose.setContent { StrideTheme { OnboardingScreen(OnboardingUiState(step = OnboardingStep.PARQ), onEvent = {}) } }
        PARQ_QUESTIONS.forEachIndexed { index, question ->
            compose.onNodeWithTag("parq_yes_$index").performScrollTo()
                .assertContentDescriptionEquals("Yes: $question")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            compose.onNodeWithTag("parq_no_$index").assertContentDescriptionEquals("No: $question")
        }
    }

    /** AUD-033: rememberSaveable UI state in the profile form (the open race-date picker) survives recreation. */
    @Test
    fun profileDatePickerSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StrideTheme {
                OnboardingScreen(
                    OnboardingUiState(step = OnboardingStep.PROFILE, profile = ProfileFormState(raceDistance = RaceDistance.TEN_K)),
                    onEvent = {}
                )
            }
        }
        compose.onNodeWithText("Pick race date").performScrollTo().performClick()
        compose.onNodeWithText("OK").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun disclaimerIsShownVerbatim() {
        compose.setContent { StrideTheme { OnboardingScreen(OnboardingUiState(step = OnboardingStep.DISCLAIMER), onEvent = {}) } }
        compose.onNodeWithText(DISCLAIMER, substring = false).assertIsDisplayed()
    }
}
