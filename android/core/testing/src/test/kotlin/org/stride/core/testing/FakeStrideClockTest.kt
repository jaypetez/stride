package org.stride.core.testing

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeStrideClockTest {
    @Test
    fun defaultsToTheDemoInstantAndIsSettable() {
        val clock = FakeStrideClock()
        assertEquals(LocalDate.of(2026, 11, 1), clock.today())
        clock.instant = Instant.parse("2027-01-02T10:00:00Z")
        assertEquals(LocalDate.of(2027, 1, 2), clock.today())
    }
}
