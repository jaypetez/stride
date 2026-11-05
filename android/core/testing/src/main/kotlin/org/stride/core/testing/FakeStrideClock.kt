package org.stride.core.testing

import java.time.Instant
import java.time.ZoneId
import org.stride.core.common.StrideClock

/** A settable clock for tests. Defaults to the same instant the demo dataset is pinned to. */
class FakeStrideClock(var instant: Instant = DEFAULT_NOW, private val zoneId: ZoneId = ZoneId.of("UTC")) : StrideClock {
    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId

    companion object {
        val DEFAULT_NOW: Instant = Instant.parse("2026-11-01T07:00:00Z")
    }
}
