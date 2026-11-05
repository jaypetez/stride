package org.stride.core.science.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * JavaScript numeric/date semantics the TS engine relies on. Reproducing them exactly is what keeps
 * rounded outputs (toFixed, Math.round) and interpolated strings identical to packages/core.
 */

/** JS truthiness for an optional number: null, 0 and NaN are falsy. */
internal fun Double?.truthy(): Boolean = this != null && this != 0.0 && !this.isNaN()

/** JS truthiness for an optional int. */
internal fun Int?.truthy(): Boolean = this != null && this != 0

/**
 * `Number(x.toFixed(digits))`. JS toFixed rounds the EXACT binary value half away from zero, which is
 * BigDecimal(double) with HALF_UP; doubles are never re-parsed from a decimal approximation first.
 */
internal fun toFixed(x: Double, digits: Int): Double = toFixedString(x, digits).toDouble()

/** `x.toFixed(digits)` as a string, including JS's "-0.0" for small negatives. */
internal fun toFixedString(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    val negative = x < 0
    val abs = BigDecimal(kotlin.math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    return if (negative) "-$abs" else abs
}

/** JS `Math.round`: nearest integer, ties toward +Infinity (Java's Math.round has the same rule). */
internal fun jsRound(x: Double): Double = if (x.isNaN() || x.isInfinite()) x else Math.round(x).toDouble()

/** JS `String(n)` for the values that appear in coaching text (integers and short decimals). */
internal fun jsString(n: Double): String = when {
    n.isNaN() -> "NaN"
    n.isInfinite() -> if (n > 0) "Infinity" else "-Infinity"
    n == Math.rint(n) && kotlin.math.abs(n) < 1e21 -> n.toLong().toString()
    else -> BigDecimal(n.toString()).stripTrailingZeros().toPlainString()
}

/** Calendar-day helpers on "YYYY-MM-DD" keys (UTC, DST-free). Mirrors packages/core/src/science/dates.ts. */
object DateKeys {
    /** First 10 chars of an ISO datetime or date. Mirrors dates.ts#toDateKey. */
    fun toDateKey(iso: String): String = iso.take(10)

    /** A key or a full ISO datetime, normalised to its calendar day. Mirrors dates.ts#keyToMs. */
    private fun parseDay(keyOrIso: String): LocalDate = LocalDate.parse(toDateKey(keyOrIso))

    /** Mirrors dates.ts#addDays. */
    fun addDays(key: String, n: Int): String = parseDay(key).plusDays(n.toLong()).toString()

    /** Whole calendar days from [a] to [b] (keys or ISO datetimes). Mirrors dates.ts#daysBetween. */
    fun daysBetween(a: String, b: String): Int = ChronoUnit.DAYS.between(parseDay(a), parseDay(b)).toInt()

    /** Inclusive list of keys from [start] to [end] (empty when end < start). Mirrors dates.ts#eachDay. */
    fun eachDay(start: String, end: String): List<String> {
        val total = daysBetween(start, end)
        val first = parseDay(start)
        return (0..total).map { first.plusDays(it.toLong()).toString() }
    }
}
