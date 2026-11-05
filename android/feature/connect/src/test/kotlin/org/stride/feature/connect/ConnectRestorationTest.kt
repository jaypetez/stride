package org.stride.feature.connect

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.ui.profile.toUtcMillis

/** AUD-033 / AUD-037: the key-expiry picker survives recreation and only offers today or later. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class ConnectRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 1)

    @Before
    fun accessibility() = compose.enableAccessibilityChecks()

    @Test
    fun openExpiryPickerSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StrideTheme {
                ConnectScreen(
                    state = ConnectUiState(loading = false, today = today, expiry = today.plusDays(90)),
                    onEvent = {},
                    onOpenGarmin = {},
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Change").performScrollTo().performClick()
        compose.onNodeWithText("OK").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("OK").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun pastDatesAreNotSelectable() {
        val dates = FutureDates(today)
        assertFalse(dates.isSelectableDate(today.minusDays(1).toUtcMillis()))
        assertTrue(dates.isSelectableDate(today.toUtcMillis()))
        assertTrue(dates.isSelectableDate(today.plusDays(200).toUtcMillis()))
        assertFalse(dates.isSelectableYear(today.year - 1))
    }
}
