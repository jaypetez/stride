package org.stride.core.ui

import android.text.format.DateFormat
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAccessor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.stride.core.model.UnitSystem

/** The user's display units; screens read it instead of threading it through every call. */
val LocalUnitSystem = staticCompositionLocalOf { UnitSystem.METRIC }

/**
 * Whether times use the 24-hour clock. The app provides the device setting
 * (`DateFormat.is24HourFormat`); previews and screenshots default to 24-hour.
 */
val LocalIs24HourClock = staticCompositionLocalOf { true }

/**
 * Display formatting only — never arithmetic that produces a training number
 * (compute-in-code lives in core:science). All inputs are SI.
 */
object Format {
    private const val METRES_PER_MILE = 1609.344
    private const val FEET_PER_METRE = 3.28084

    fun distanceUnit(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "mi" else "km"

    /** 10234 m → "10.23" (km) or "6.36" (mi). */
    fun distance(metres: Double, units: UnitSystem, decimals: Int = 2): String {
        val v = if (units == UnitSystem.IMPERIAL) metres / METRES_PER_MILE else metres / 1000.0
        return "%.${decimals}f".format(Locale.ROOT, v)
    }

    fun distanceWithUnit(metres: Double, units: UnitSystem, decimals: Int = 2) =
        "${distance(metres, units, decimals)} ${distanceUnit(units)}"

    fun elevation(metres: Double, units: UnitSystem): String =
        if (units == UnitSystem.IMPERIAL) "${(metres * FEET_PER_METRE).roundToInt()} ft" else "${metres.roundToInt()} m"

    /** Seconds → "1:02:03" or "54:12". */
    fun duration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** Seconds → "5h 20m" / "42m". */
    fun durationShort(seconds: Double): String {
        val totalMin = (seconds / 60).roundToInt()
        return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
    }

    fun paceUnit(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "/mi" else "/km"

    /** Speed (m/s) → pace "5:19" per km or mile; "–" when not moving. */
    fun pace(speedMps: Double?, units: UnitSystem): String {
        if (speedMps == null || speedMps <= 0.05 || speedMps.isNaN()) return "–"
        val metres = if (units == UnitSystem.IMPERIAL) METRES_PER_MILE else 1000.0
        val secs = (metres / speedMps).roundToLong()
        return "%d:%02d".format(Locale.ROOT, secs / 60, secs % 60)
    }

    fun paceWithUnit(speedMps: Double?, units: UnitSystem) = "${pace(speedMps, units)} ${paceUnit(units)}"

    /** Pace given as seconds per km (as core:science reports it). */
    fun paceFromSecPerKm(secPerKm: Double?, units: UnitSystem): String =
        pace(secPerKm?.takeIf { it > 0 }?.let { 1000.0 / it }, units)

    fun number(value: Double, decimals: Int = 1): String = "%.${decimals}f".format(Locale.ROOT, value)

    /** Signed with an explicit "+" (TSB). */
    fun signed(value: Double, decimals: Int = 0): String {
        val s = "%.${decimals}f".format(Locale.ROOT, abs(value))
        return when {
            value > 0 && s.trim('0', '.').isNotEmpty() -> "+$s"
            value < 0 && s.trim('0', '.').isNotEmpty() -> "−$s"
            else -> s
        }
    }

    /** Signed distance change, e.g. "+3.5" / "−1.2" (km or mi). */
    fun signedDistance(metres: Double, units: UnitSystem, decimals: Int = 1): String {
        val v = if (units == UnitSystem.IMPERIAL) metres / METRES_PER_MILE else metres / 1000.0
        return signed(v, decimals)
    }

    /** Signed duration change, e.g. "+42m" / "−1h 5m" / "0m". */
    fun signedDurationShort(seconds: Double): String {
        val body = durationShort(abs(seconds))
        return when {
            body == "0m" -> body
            seconds > 0 -> "+$body"
            else -> "−$body"
        }
    }

    fun percent(fraction: Double, decimals: Int = 0): String = "%.${decimals}f%%".format(Locale.ROOT, fraction * 100)

    // Dates follow the device locale (AUD-034): each pattern is the locale's best match for a
    // skeleton, so en-GB reads "Sun 1 Nov" and en-US "Sun, Nov 1". Formatters are cached per locale.
    private val patterns = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

    private fun formatter(skeleton: String, locale: Locale): DateTimeFormatter = patterns.getOrPut(locale to skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** "Sun 1 Nov" (en-GB) / "Sun, Nov 1" (en-US). */
    fun day(date: LocalDate, locale: Locale = Locale.getDefault()): String = formatter("EEEdMMM", locale).format(date)

    fun day(isoDate: String, locale: Locale = Locale.getDefault()): String = day(LocalDate.parse(isoDate), locale)

    /** "Sun, 1 Nov 2026" (en-GB) / "Sun, Nov 1, 2026" (en-US). */
    fun dayWithYear(date: LocalDate, locale: Locale = Locale.getDefault()): String = formatter("EEEdMMMyyyy", locale).format(date)

    /** "1 Nov" (en-GB) / "Nov 1" (en-US): chart axes and plan rows. */
    fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String = formatter("dMMM", locale).format(date)

    fun shortDate(isoDate: String, locale: Locale = Locale.getDefault()): String = shortDate(LocalDate.parse(isoDate), locale)

    /** "November 2026": month headers. */
    fun monthYear(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        formatter("MMMMyyyy", locale).format(month.atDay(1))

    /** "07:05" or "7:05 AM", following the device's 24-hour setting ([LocalIs24HourClock]). */
    fun time(time: TemporalAccessor, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        formatter(if (is24Hour) "Hmm" else "hmma", locale).format(time)

    /** "Sun 1 Nov · 07:05" in the activity's own zone (device zone when unknown). */
    fun dayTime(epochSec: Long, zone: String?, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochSecond(epochSec).atZone(zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())
        return "${day(at.toLocalDate(), locale)} · ${time(at, is24Hour, locale)}"
    }

    /** "Sun, 1 Nov 2026, 07:05": an instant in [zone], e.g. the last sync. */
    fun dateTime(instant: Instant, zone: ZoneId, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val at = instant.atZone(zone)
        return "${dayWithYear(at.toLocalDate(), locale)}, ${time(at, is24Hour, locale)}"
    }

    fun weekday(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
}
