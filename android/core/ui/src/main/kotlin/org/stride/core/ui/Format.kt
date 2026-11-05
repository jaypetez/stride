package org.stride.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.stride.core.model.UnitSystem

/** The user's display units; screens read it instead of threading it through every call. */
val LocalUnitSystem = staticCompositionLocalOf { UnitSystem.METRIC }

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

    fun percent(fraction: Double, decimals: Int = 0): String = "%.${decimals}f%%".format(Locale.ROOT, fraction * 100)

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.UK)

    fun day(date: LocalDate): String = DAY.format(date)

    fun day(isoDate: String): String = day(LocalDate.parse(isoDate))

    fun dayTime(epochSec: Long, zone: String?): String =
        DAY_TIME.format(
            Instant.ofEpochSecond(epochSec).atZone(zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())
        )

    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)
}
