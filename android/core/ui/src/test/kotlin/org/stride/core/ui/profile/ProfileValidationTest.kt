package org.stride.core.ui.profile

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.stride.core.model.AthleteProfile
import org.stride.core.model.RaceGoal
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem

/** Profile form validation (MVP-013 / MVP-021). */
class ProfileValidationTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val valid = ProfileFormState(name = " Ada ", birthYear = "1990", sex = Sex.FEMALE, units = UnitSystem.IMPERIAL)

    @Test
    fun minimalFormIsValid() {
        val result = valid.validate(today)
        assertTrue(result.isValid)
        val profile = assertNotNull(result.profile)
        assertEquals("Ada", profile.name)
        assertEquals(1990, profile.birthYear)
        assertEquals(UnitSystem.IMPERIAL, profile.unitSystem)
        assertNull(profile.maxHr)
        assertNull(profile.raceGoal)
    }

    @Test
    fun nameAndBirthYearAreRequired() {
        val result = ProfileFormState().validate(today)
        assertEquals(setOf(ProfileField.NAME, ProfileField.BIRTH_YEAR), result.errors.keys)
        assertNull(result.profile)
    }

    @Test
    fun birthYearMustGiveAPlausibleAge() {
        assertTrue(ProfileField.BIRTH_YEAR in valid.copy(birthYear = "2020").validate(today).errors)
        assertTrue(ProfileField.BIRTH_YEAR in valid.copy(birthYear = "1900").validate(today).errors)
        assertTrue(valid.copy(birthYear = "2013").validate(today).isValid)
    }

    @Test
    fun heartRatesAreRangeCheckedAndOrdered() {
        assertTrue(ProfileField.MAX_HR in valid.copy(maxHr = "300").validate(today).errors)
        assertTrue(ProfileField.RESTING_HR in valid.copy(restingHr = "5").validate(today).errors)
        assertTrue(ProfileField.THRESHOLD_HR in valid.copy(maxHr = "180", thresholdHr = "185").validate(today).errors)
        val ok = valid.copy(maxHr = "190", restingHr = "48", thresholdHr = "172").validate(today)
        assertEquals(190, ok.profile?.maxHr)
        assertEquals(48, ok.profile?.restingHr)
        assertEquals(172, ok.profile?.thresholdHr)
    }

    @Test
    fun raceGoalNeedsAFutureDate() {
        assertTrue(ProfileField.RACE_DATE in valid.copy(raceDistance = RaceDistance.TEN_K).validate(today).errors)
        assertTrue(
            ProfileField.RACE_DATE in valid.copy(raceDistance = RaceDistance.TEN_K, raceDate = today.minusDays(1)).validate(today).errors
        )
        val goal = valid.copy(raceDistance = RaceDistance.HALF, raceDate = today.plusWeeks(10)).validate(today).profile?.raceGoal
        assertEquals(RaceGoal(21_097.5, "2027-01-10"), goal)
    }

    @Test
    fun roundTripsAndKeepsFieldsTheFormDoesNotEdit() {
        val base = AthleteProfile(
            name = "Ada",
            birthYear = 1990,
            maxHr = 190,
            thresholdPaceSecPerKm = 255.0,
            raceGoal = RaceGoal(10_000.0, "2026-12-01", targetTimeSec = 2700.0)
        )
        val result = ProfileFormState.from(base).validate(today, base)
        assertEquals(base, result.profile)
    }
}
