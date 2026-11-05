package org.stride.core.data

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.stride.core.data.insights.VolumeDelta
import org.stride.core.data.insights.WeeklyVolume
import org.stride.core.data.insights.deltaFrom
import org.stride.core.data.insights.paceSecPerKm
import org.stride.core.data.insights.zoneShares

class DisplayTest {
    @Test
    fun weekOverWeekDelta() {
        val thisWeek = WeeklyVolume(distanceM = 42_000.0, durationSec = 14_400.0, load = 310.0, runs = 5)
        val lastWeek = WeeklyVolume(distanceM = 38_500.0, durationSec = 13_800.0, load = 335.0, runs = 4)
        assertEquals(VolumeDelta(3_500.0, 600.0, -25.0, 1), thisWeek.deltaFrom(lastWeek))
    }

    @Test
    fun zoneSharesSumToOne() {
        val shares = zoneShares(listOf(60.0, 1800.0, 600.0, 120.0, 0.0))
        assertEquals(1.0, shares.sum(), 1e-12)
        assertEquals(1800.0 / 2580.0, shares[1], 1e-12)
        assertTrue(zoneShares(listOf(0.0, 0.0)).isEmpty())
    }

    @Test
    fun paceFromSpeed() {
        assertEquals(300.0, paceSecPerKm(1000.0 / 300), 1e-9)
        assertTrue(paceSecPerKm(Double.NaN).isNaN())
        assertTrue(paceSecPerKm(0.1).isNaN())
    }
}
