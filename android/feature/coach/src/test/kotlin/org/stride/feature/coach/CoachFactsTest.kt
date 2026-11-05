package org.stride.feature.coach

import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.stride.core.model.UnitSystem

/** The FACTS payload holds computed metrics only — never streams, laps or raw data (MVP-019). */
class CoachFactsTest {
    private val activityKeys = setOf(
        "date", "sport", "distance", "duration_min", "avg_pace", "avg_hr_bpm", "max_hr_bpm", "elevation_gain_m", "load",
        "load_method", "intensity_factor", "hr_zone_minutes", "vdot", "efficiency_factor", "aerobic_decoupling_pct"
    )

    private fun keys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys).toSet()
        else -> emptySet()
    }

    private fun arrays(e: JsonElement): List<JsonArray> = when (e) {
        is JsonObject -> e.values.flatMap(::arrays)
        is JsonArray -> listOf(e) + e.flatMap(::arrays)
        else -> emptyList()
    }

    @Test
    fun activityPayloadContainsOnlyFactFields() {
        val json = buildActivityFacts(Samples.detail, UnitSystem.METRIC, ZoneOffset.UTC).toJson()
        val root = Json.parseToJsonElement(json)
        assertEquals(setOf("subject", "distance_unit", "activity"), root.jsonObject.keys)
        assertTrue(activityKeys.containsAll(root.jsonObject.getValue("activity").jsonObject.keys))
        val all = keys(root)
        listOf("streams", "laps", "timeSec", "time_sec", "heartRate", "speedMps", "name", "id", "calories").forEach {
            assertFalse(it in all, "payload must not contain $it")
        }
        assertFalse(json.contains("Morning Run"))
        // The only array is the five HR-zone minutes: no per-sample streams.
        val onlyArrays = arrays(root)
        assertEquals(1, onlyArrays.size)
        assertEquals(5, onlyArrays.single().size)
        assertTrue(json.length < 1_000, "facts stay small: ${json.length} chars")
    }

    @Test
    fun activityFactsAreRoundedComputedMetrics() {
        val a = buildActivityFacts(Samples.detail, UnitSystem.METRIC, ZoneOffset.UTC).activity!!
        assertEquals("2026-11-01", a.date)
        assertEquals(10.01, a.distance)
        assertEquals(50.0, a.durationMin)
        assertEquals("5:00", a.avgPace)
        assertEquals(68.4, a.load)
        assertEquals("rTSS (pace)", a.loadMethod)
        assertEquals(0.83, a.intensityFactor)
        assertEquals(listOf(2.0, 25.0, 18.0, 5.0, 0.0), a.hrZoneMinutes)
        assertEquals(48.3, a.vdot)
        assertEquals(1.32, a.efficiencyFactor)
        assertEquals(3.8, a.decouplingPct)
    }

    @Test
    fun imperialUnitsConvertDistanceAndPace() {
        val f = buildActivityFacts(Samples.detail, UnitSystem.IMPERIAL, ZoneOffset.UTC)
        assertEquals("mi", f.distanceUnit)
        assertEquals(6.22, f.activity!!.distance)
        assertEquals("8:02", f.activity?.avgPace)
    }

    @Test
    fun trendPayloadHasPmcAcwrAndWeeklyVolume() {
        val f = buildTrendFacts(Samples.today, vdot = 48.27, units = UnitSystem.METRIC)
        assertNull(f.activity)
        val fit = f.fitness!!
        assertEquals(52.4, fit.ctl)
        assertEquals(61.0, fit.atl)
        assertEquals(-8.7, fit.tsb)
        assertEquals("Neutral", fit.formBand)
        assertEquals(1.16, fit.acwr)
        assertEquals("ok", fit.acwrFlag)
        assertEquals(WeekFacts(32.4, 180.0, 210.5, 4), fit.thisWeek)
        assertEquals(48.3, fit.vdot)
        assertTrue(arrays(Json.parseToJsonElement(f.toJson())).isEmpty())
    }

    @Test
    fun userPromptCarriesTheFactsJson() {
        val facts = buildTrendFacts(Samples.today, null, UnitSystem.METRIC)
        val prompt = coachUserPrompt(facts)
        assertTrue(prompt.startsWith("FACTS:\n${facts.toJson()}"))
    }
}
