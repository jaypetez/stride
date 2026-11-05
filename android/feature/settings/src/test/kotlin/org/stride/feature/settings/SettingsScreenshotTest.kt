package org.stride.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.AthleteProfile
import org.stride.core.model.RaceGoal
import org.stride.core.model.Sex
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot
import org.stride.core.ui.UiText
import org.stride.core.ui.profile.ProfileFormState

/** The "You" tab, profile edit and licences (MVP-021). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class SettingsScreenshotTest {
    private val profile =
        AthleteProfile(name = "Ada Lovelace", birthYear = 1990, sex = Sex.FEMALE, maxHr = 188, raceGoal = RaceGoal(21_097.5, "2027-03-14"))
    private val state = SettingsUiState(
        loading = false,
        profile = profile,
        garminConnected = true,
        lastSync = Instant.parse("2026-11-01T07:00:00Z"),
        zone = ZoneOffset.UTC,
        syncStatus = UiText.plural(R.plurals.settings_synced, 3, 3),
        version = "0.1.0-mvp"
    )

    @Test fun connected() = snap("settings_connected", false) { SettingsScreen(state, onEvent = {}, onNavigate = {}, onExport = {}) }

    @Test
    fun demoAndCoach() = snap("settings_demo", true) {
        SettingsScreen(state.copy(garminConnected = false, demoMode = true, coachEnabled = true), onEvent = {
        }, onNavigate = {}, onExport = {})
    }

    @Test
    fun keyRejected() = snap("settings_rejected", false) {
        SettingsScreen(state.copy(garminKeyRejected = true), onEvent = {}, onNavigate = {}, onExport = {})
    }

    @Test
    fun deleteDialog() = snap("settings_delete", false) {
        SettingsScreen(state.copy(confirmDelete = true), onEvent = {}, onNavigate = {}, onExport = {})
    }

    @Test
    fun profileEdit() = snap("profile_edit", false) {
        ProfileEditScreen(
            ProfileEditUiState(loading = false, form = ProfileFormState.from(profile).copy(raceDate = LocalDate.of(2027, 3, 14))),
            onChange = {},
            onSave = {},
            onBack = {}
        )
    }

    /** AUD-032: settings could not be read. */
    @Test fun error() = snap("settings_error", false) {
        SettingsScreen(SettingsUiState(loading = false, loadFailed = true), onEvent = {}, onNavigate = {}, onExport = {})
    }

    /** AUD-035: large text and right-to-left. */
    @Test fun connectedFont150() = snap("settings_connected_font150", false, ScreenshotVariant.FONT_150) {
        SettingsScreen(state, onEvent = {}, onNavigate = {}, onExport = {})
    }

    @Test fun connectedRtl() = snap("settings_connected_rtl", false, ScreenshotVariant.RTL) {
        SettingsScreen(state, onEvent = {}, onNavigate = {}, onExport = {})
    }

    @Test fun licences() = snap("licences", false) { LicencesScreen(onBack = {}) }

    private fun snap(
        name: String,
        dark: Boolean,
        variant: ScreenshotVariant = ScreenshotVariant.DEFAULT,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) = captureScreenshot("src/test/screenshots/$name.png", variant = variant, accessibilityChecks = !dark) {
        StrideTheme(darkTheme = dark) { content() }
    }
}
