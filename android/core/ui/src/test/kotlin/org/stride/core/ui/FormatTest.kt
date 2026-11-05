package org.stride.core.ui

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.model.UnitSystem

/** Number formatting is locale-independent; dates follow the device locale and clock setting (AUD-034). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
        assertEquals("Sun 1 Nov", Format.day("2026-11-01", Locale.UK))
    }

    @Test
    fun datesFollowTheLocale() {
        val date = LocalDate.of(2026, 11, 1)
        assertEquals("Sun 1 Nov", Format.day(date, Locale.UK))
        assertEquals("Sun, Nov 1", Format.day(date, Locale.US))
        assertEquals("1 Nov", Format.shortDate(date, Locale.UK))
        assertEquals("Nov 1", Format.shortDate(date, Locale.US))
        assertEquals("November 2026", Format.monthYear(YearMonth.of(2026, 11), Locale.UK))
        kotlin.test.assertTrue(Format.day(date, Locale.GERMANY).startsWith("So"), "German weekday names")
    }

    @Test
    fun timesFollowThe24HourSetting() {
        val epoch = Instant.parse("2026-11-01T19:05:00Z").epochSecond
        assertEquals("Sun 1 Nov · 19:05", Format.dayTime(epoch, "UTC", is24Hour = true, locale = Locale.UK))
        assertEquals("Sun, Nov 1 · 7:05 PM", Format.dayTime(epoch, "UTC", is24Hour = false, locale = Locale.US))
        assertEquals(
            "Sun, 1 Nov 2026, 19:05",
            Format.dateTime(Instant.ofEpochSecond(epoch), ZoneOffset.UTC, is24Hour = true, locale = Locale.UK)
        )
    }

    @Test
    fun signedDeltas() {
        assertEquals("+3.5", Format.signedDistance(3_500.0, UnitSystem.METRIC))
        assertEquals("−1.0", Format.signedDistance(-1_609.344, UnitSystem.IMPERIAL))
        assertEquals("+1h 5m", Format.signedDurationShort(3_900.0))
        assertEquals("−42m", Format.signedDurationShort(-2_520.0))
        assertEquals("0m", Format.signedDurationShort(10.0))
    }
}
