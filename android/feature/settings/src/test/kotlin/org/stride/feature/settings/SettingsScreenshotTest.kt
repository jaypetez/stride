package org.stride.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.AthleteProfile
import org.stride.core.model.RaceGoal
import org.stride.core.model.Sex
import org.stride.core.testing.ScreenshotDefaults
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
        lastSync = "Sun 1 Nov 2026, 07:00",
        syncStatus = "Synced 3 new activities",
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

    @Test fun licences() = snap("licences", false) { LicencesScreen(onBack = {}) }

    private fun snap(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png") { StrideTheme(darkTheme = dark) { content() } }
}
