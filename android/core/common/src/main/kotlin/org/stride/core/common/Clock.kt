package org.stride.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Injectable clock so "today" is deterministic in tests, screenshots and demo
 * mode (mirrors STRIDE_NOW / deps.nowIso in packages/core).
 */
interface StrideClock {
    fun now(): Instant

    fun zone(): ZoneId

    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
}

class SystemStrideClock : StrideClock {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedStrideClock(private val instant: Instant, private val zoneId: ZoneId = ZoneId.of("UTC")) : StrideClock {
    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId
}
