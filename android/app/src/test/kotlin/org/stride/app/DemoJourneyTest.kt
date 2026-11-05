package org.stride.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.data.demo.DemoMode
import org.stride.core.datastore.UserPreferences
import org.stride.core.model.AthleteProfile
import org.stride.core.testing.ScreenshotDefaults

/**
 * The real app (Hilt graph, real feature screens, demo data through the real
 * pipeline): Today → Runs → activity detail → back (mirrors the Maestro flow).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE, application = HiltTestApplication::class)
class DemoJourneyTest {
    private val hilt = HiltAndroidRule(this)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(compose)

    @Inject lateinit var demoMode: DemoMode

    @Inject lateinit var prefs: UserPreferences

    @Test
    fun todayRunsDetailAndBack() {
        hilt.inject()
        runBlocking {
            prefs.completeOnboarding(AthleteProfile(name = "Journey", birthYear = 1990), parqFlagged = false)
            demoMode.enter()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Next workout")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Runs tab").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(openRun).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(openRun).onFirst().performClick()
            try {
                compose.waitUntil(15_000) { compose.onAllNodes(hasText("Training load")).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: Throwable) {
                throw AssertionError("Activity detail did not render. Tree:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
            }
            compose.onNodeWithText("Splits").performScrollTo().assertIsDisplayed()
        }
    }

    private val openRun = SemanticsMatcher("click label 'Open run'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open run" }

    private fun hasText(text: String) = androidx.compose.ui.test.hasText(text, substring = true)
}
