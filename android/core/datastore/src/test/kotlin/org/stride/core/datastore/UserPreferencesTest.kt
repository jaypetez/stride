package org.stride.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.model.AthleteProfile
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onboardingProfileAndWipe() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("prefs.preferences_pb").also { it.delete() }
        val prefs = UserPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertFalse(prefs.current().onboardingComplete)
        val profile = AthleteProfile(name = "Sam", birthYear = 1990, sex = Sex.FEMALE, maxHr = 188)
        prefs.completeOnboarding(profile, parqFlagged = true)
        prefs.setUnitSystem(UnitSystem.IMPERIAL)
        val s = prefs.current()
        assertTrue(s.onboardingComplete && s.disclaimerAccepted && s.parqFlagged)
        assertEquals(profile.copy(unitSystem = UnitSystem.IMPERIAL), s.profile)
        prefs.wipe()
        assertEquals(UserSettings(), prefs.current())
    }
}
