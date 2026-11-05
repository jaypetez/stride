package org.stride.core.ui

import kotlin.test.assertEquals
import org.junit.Test
import org.stride.core.model.UnitSystem

class FormatTest {
    @Test
    fun distancesAndPaces() {
        assertEquals("10.23", Format.distance(10_234.0, UnitSystem.METRIC))
        assertEquals("6.36", Format.distance(10_234.0, UnitSystem.IMPERIAL))
        assertEquals("5:00", Format.pace(1000.0 / 300, UnitSystem.METRIC))
        assertEquals("8:03", Format.pace(1000.0 / 300, UnitSystem.IMPERIAL))
        assertEquals("–", Format.pace(0.0, UnitSystem.METRIC))
        assertEquals("4:30", Format.paceFromSecPerKm(270.0, UnitSystem.METRIC))
    }

    @Test
    fun durationsAndSigns() {
        assertEquals("54:12", Format.duration(3252.0))
        assertEquals("1:02:03", Format.duration(3723.0))
        assertEquals("1h 2m", Format.durationShort(3723.0))
        assertEquals("+6", Format.signed(6.2))
        assertEquals("−12", Format.signed(-12.4))
        assertEquals("0", Format.signed(0.2))
        assertEquals("Sun 1 Nov", Format.day("2026-11-01"))
    }
}
