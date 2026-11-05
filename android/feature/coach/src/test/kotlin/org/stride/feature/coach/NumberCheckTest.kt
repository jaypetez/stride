package org.stride.feature.coach

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import org.stride.core.model.UnitSystem

/** Every number in the coach's reply must come from the facts (MVP-019 post-check). */
class NumberCheckTest {
    private val facts = buildTrendFacts(Samples.today, vdot = 48.27, units = UnitSystem.METRIC)
    private val factsJson = Json.parseToJsonElement(facts.toJson())
    private val allowed = AllowedFacts(factsJson)

    @Test
    fun exactAndRoundedNumbersPass() {
        assertTrue(matchesFact("52.4", allowed.numbers))
        assertTrue(matchesFact("52", allowed.numbers), "rounded to the nearest whole number")
        assertTrue(matchesFact("1.2", allowed.numbers), "ACWR 1.16 at one decimal")
        assertTrue(matchesFact("32.4", allowed.numbers))
        assertTrue(matchesFact("4", allowed.numbers), "runs this week")
        assertTrue(matchesFact("116", allowed.numbers), "ACWR 1.16 written as a percentage")
    }

    @Test
    fun signAndFormattingVariantsPass() {
        assertTrue(matchesFact("-8.7", allowed.numbers))
        assertTrue(matchesFact("−8.7", allowed.numbers))
        assertTrue(matchesFact("8.7", allowed.numbers), "\"8.7 below zero\"")
        assertTrue(matchesFact("9", allowed.numbers))
    }

    @Test
    fun inventedNumbersFail() {
        assertFalse(matchesFact("55", allowed.numbers))
        assertFalse(matchesFact("52.9", allowed.numbers), "52.37 rounds to 52.4, not 52.9")
        assertFalse(matchesFact("3:45", allowed.numbers))
        assertEquals(listOf("1:45:00"), unverifiedNumbers("You could run a half in 1:45:00.", allowed))
        assertEquals(listOf("2027-04-01"), unverifiedNumbers("Race on 2027-04-01.", allowed))
    }

    @Test
    fun zoneLabelsListMarkersAndNamesAreNotNumbers() {
        assertTrue(unverifiedNumbers("1. Keep most running in zone 2 and Z1.", allowed).isEmpty())
        assertTrue(unverifiedNumbers("- Your VO2max proxy (VDOT 48.3) suits a 10K.", allowed).isEmpty())
        assertTrue(unverifiedNumbers("Facts are as of 2026-11-01.", allowed).isEmpty())
    }

    @Test
    fun sentencesWithInventedNumbersAreStripped() {
        val reply = "Your fitness (CTL) is 52.4 and fatigue is 61. Form sits at -8.7, which is neutral. " +
            "You should hit 60 km next week.\nThis week you ran 32.4 km over 4 runs."
        val checked = checkReplyNumbers(reply, factsJson)
        assertFalse(checked.rejected)
        assertEquals(listOf("You should hit 60 km next week."), checked.removed)
        assertEquals(
            "Your fitness (CTL) is 52.4 and fatigue is 61. Form sits at -8.7, which is neutral.\nThis week you ran 32.4 km over 4 runs.",
            checked.text
        )
    }

    @Test
    fun mostlyInventedRepliesAreRejected() {
        val reply = "Run 70 km. Do 6 x 800 m. Your fitness is 52.4."
        val checked = checkReplyNumbers(reply, factsJson)
        assertTrue(checked.rejected)
        assertEquals("", checked.text)
        assertEquals(2, checked.removed.size)
    }

    @Test
    fun numberFreeProseIsKept() {
        val checked = checkReplyNumbers("Nice steady week. Keep the easy days easy.", factsJson)
        assertEquals("Nice steady week. Keep the easy days easy.", checked.text)
        assertTrue(checked.removed.isEmpty())
    }
}
