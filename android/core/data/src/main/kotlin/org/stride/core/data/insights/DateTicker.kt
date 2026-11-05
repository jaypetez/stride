package org.stride.core.data.insights

import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.stride.core.common.StrideClock

/** Longest wait between checks, so a changed clock or time zone is picked up within minutes. */
const val DATE_POLL_MS: Long = 15 * 60 * 1000L

/**
 * The local date now, and again each time it changes (AUD-031). Screens combine it in so
 * one left open past midnight moves to the new day without user action. Wakes just after
 * the next local midnight, and at least every [maxPollMs].
 */
fun StrideClock.dates(maxPollMs: Long = DATE_POLL_MS): Flow<LocalDate> = flow {
    while (true) {
        val now = now()
        val zone = zone()
        val today = now.atZone(zone).toLocalDate()
        emit(today)
        val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        val untilMidnightMs = Duration.between(now, nextMidnight).toMillis() + 1
        delay(untilMidnightMs.coerceIn(1, maxPollMs))
    }
}.distinctUntilChanged()
