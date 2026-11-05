package org.stride.core.garmin

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DateWindowsTest {
    @Test
    fun windowsCoverRangeNewestFirstWithoutGapsOrOverlap() {
        val start = LocalDate.parse("2026-05-09")
        val end = LocalDate.parse("2026-11-04")
        val windows = dateWindows(start, end, 28)
        assertEquals(end, windows.first().end)
        assertEquals(start, windows.last().start)
        windows.zipWithNext().forEach { (newer, older) -> assertEquals(newer.start.minusDays(1), older.end) }
        assertTrue(windows.all { java.time.temporal.ChronoUnit.DAYS.between(it.start, it.end) < 28 })
    }

    @Test
    fun singleDayAndEmptyRanges() {
        val d = LocalDate.parse("2026-11-01")
        assertEquals(listOf(DateWindow(d, d)), dateWindows(d, d, 28))
        assertEquals(emptyList(), dateWindows(d, d.minusDays(1), 28))
    }
}
