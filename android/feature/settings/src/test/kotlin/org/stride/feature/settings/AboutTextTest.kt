package org.stride.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.ui.GARMIN_ATTRIBUTION
import org.stride.core.ui.PRIVACY_NOTE

/** About shows the contract texts and the licences list covers what ships (MVP-021). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class AboutTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aboutShowsPrivacyDisclaimerAndAttribution() {
        compose.setContent {
            StrideTheme {
                SettingsScreen(SettingsUiState(loading = false, version = "1.2.3"), onEvent = {}, onNavigate = {}, onExport = {})
            }
        }
        compose.onNodeWithText(PRIVACY_NOTE).performScrollTo()
        compose.onNodeWithText(DISCLAIMER).performScrollTo()
        compose.onNodeWithText(GARMIN_ATTRIBUTION).performScrollTo()
        compose.onNodeWithText("Stride 1.2.3").performScrollTo()
        compose.onNodeWithText("Open-source licences").performScrollTo()
    }

    @Test
    fun privacyNoteIsExact() {
        assertTrue(PRIVACY_NOTE == "Your data never leaves this device except to Garmin, and to Anthropic only if you enable the coach.")
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
