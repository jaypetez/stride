package org.stride.core.science

import kotlin.test.Test
import kotlin.test.assertEquals
import org.stride.core.science.util.DateKeys
import org.stride.core.science.util.jsRound
import org.stride.core.science.util.jsString
import org.stride.core.science.util.toFixed
import org.stride.core.science.util.toFixedString

/** The JS rounding/printing rules the port depends on (values checked against node). */
class JsSemanticsTest {
    @Test
    fun toFixedRoundsTheExactBinaryValueHalfAwayFromZero() {
        assertEquals("1.00", toFixedString(1.005, 2)) // 1.005 is 1.00499999... in binary
        assertEquals("3", toFixedString(2.5, 0))
        assertEquals("-3", toFixedString(-2.5, 0))
        assertEquals("-0.0", toFixedString(-0.04, 1))
        assertEquals("0.0", toFixedString(-0.0, 1))
        assertEquals("1.3", toFixedString(1.25, 1))
        assertEquals(40.12, toFixed(40.1249999, 2))
    }

    @Test
    fun mathRoundTiesTowardPositiveInfinity() {
        assertEquals(3.0, jsRound(2.5))
        assertEquals(-2.0, jsRound(-2.5))
        assertEquals(0.0, jsRound(0.49999999999999994))
    }

    @Test
    fun numbersPrintLikeTemplateLiterals() {
        assertEquals("5", jsString(5.0))
        assertEquals("5.5", jsString(5.5))
        assertEquals("0", jsString(-0.0))
        assertEquals("-30", jsString(-30.0))
    }

    @Test
    fun dateKeys() {
        assertEquals("2026-03-01", DateKeys.addDays("2026-02-28", 1))
        assertEquals(listOf("2026-12-31", "2027-01-01"), DateKeys.eachDay("2026-12-31", "2027-01-01"))
        assertEquals(emptyList(), DateKeys.eachDay("2026-01-02", "2026-01-01"))
        assertEquals("2026-07-08", DateKeys.toDateKey("2026-07-08T08:00:00"))
    }
}
