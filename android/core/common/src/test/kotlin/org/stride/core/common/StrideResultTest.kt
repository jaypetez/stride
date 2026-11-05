package org.stride.core.common

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrideResultTest {
    @Test
    fun mapTransformsOkAndKeepsErr() {
        val ok: StrideResult<Int, String> = StrideResult.Ok(2)
        val err: StrideResult<Int, String> = StrideResult.Err("boom")
        assertEquals(StrideResult.Ok(4), ok.map { it * 2 })
        assertEquals(err, err.map { it * 2 })
        assertEquals(2, ok.getOrNull())
        assertNull(err.getOrNull())
    }

    @Test
    fun fixedClockIsDeterministic() {
        val clock = FixedStrideClock(Instant.parse("2026-11-01T06:00:00Z"))
        assertEquals(LocalDate.of(2026, 11, 1), clock.today())
    }
}
