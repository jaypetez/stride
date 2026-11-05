package org.stride.feature.settings

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.ui.R as CoreUiR

/** About shows the contract texts and the licences list covers what ships (MVP-021); rows are accessible (AUD-035). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class AboutTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun accessibility() = compose.enableAccessibilityChecks()

    @Test
    fun aboutShowsPrivacyDisclaimerAndAttribution() {
        compose.setContent {
            StrideTheme {
                SettingsScreen(SettingsUiState(loading = false, version = "1.2.3"), onEvent = {}, onNavigate = {}, onExport = {})
            }
        }
        compose.onNodeWithText(context.getString(CoreUiR.string.privacy_note)).performScrollTo()
        compose.onNodeWithText(DISCLAIMER).performScrollTo()
        compose.onNodeWithText(context.getString(CoreUiR.string.garmin_attribution)).performScrollTo()
        compose.onNodeWithText("Stride 1.2.3").performScrollTo()
        compose.onNodeWithText("Open-source licences").performScrollTo()
    }

    @Test
    fun privacyNoteIsExact() {
        assertTrue(
            context.getString(CoreUiR.string.privacy_note) ==
                "Your data never leaves this device except to Garmin, and to Anthropic only if you enable the coach."
        )
    }

    /** AUD-035: the dynamic-colour row is one focus target with the switch role, not a clickable row plus a switch. */
    @Test
    fun switchRowIsOneToggleableTarget() {
        var checked = false
        compose.setContent {
            StrideTheme {
                SwitchRow(title = "Dynamic colour", subtitle = "Wallpaper colours", checked = checked, onChange = { checked = it })
            }
        }
        val row = compose.onNodeWithText("Dynamic colour", useUnmergedTree = false)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        // Exactly one actionable node: the Switch has no click action of its own.
        assertEquals(1, compose.onAllNodesWithText("Dynamic colour").fetchSemanticsNodes().size)
        assertEquals(
            1,
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size
        )
        row.performClick()
        assertTrue(checked)
    }

    /** AUD-033: the delete-everything dialog is VM state; the screen shows it again after recreation. */
    @Test
    fun deleteDialogShowsAfterRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StrideTheme {
                SettingsScreen(SettingsUiState(loading = false, confirmDelete = true), onEvent = {}, onNavigate = {}, onExport = {})
            }
        }
        compose.onNodeWithText("Delete all data?").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Delete all data?").assertIsDisplayed()
    }

    @Test
    fun licencesCoverShippedLibraries() {
        val names = LICENCES.joinToString(" ") { it.name + " " + it.licence }
        listOf(
            "Garmin FIT SDK", "FIT Protocol License", "Jetpack Compose", "Kotlin", "kotlinx", "Hilt", "Dagger",
            "OkHttp", "Tink", "Room", "WorkManager", "DataStore"
        ).forEach { assertTrue(names.contains(it), "licences must list $it") }
    }
}
