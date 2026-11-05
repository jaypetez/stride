package org.stride.core.garmin

import java.time.LocalDate

/** An inclusive date window. */
data class DateWindow(val start: LocalDate, val end: LocalDate)

/**
 * Splits [start]..[end] (inclusive) into windows of at most [maxDays] days,
 * newest first, so backfills fetch recent data before old data (MVP-008).
 */
fun dateWindows(start: LocalDate, end: LocalDate, maxDays: Int): List<DateWindow> {
    require(maxDays >= 1) { "maxDays must be >= 1" }
    if (end.isBefore(start)) return emptyList()
    val windows = mutableListOf<DateWindow>()
    var windowEnd = end
    while (!windowEnd.isBefore(start)) {
        val windowStart = maxOf(start, windowEnd.minusDays(maxDays - 1L))
        windows += DateWindow(windowStart, windowEnd)
        windowEnd = windowStart.minusDays(1)
    }
    return windows
}
