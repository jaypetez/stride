package org.stride.feature.coach

import kotlin.math.abs
import kotlin.math.pow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The coach's reply after the number post-check (MVP-019, compute-in-code).
 * [text] keeps only sentences whose numbers all come from the facts;
 * [removed] lists the sentences that were dropped. [rejected] means too much
 * of the reply was unverifiable to show any of it.
 */
data class CheckedReply(val text: String, val removed: List<String>, val rejected: Boolean)

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val CLOCK = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")

/** "zone 2", "Z4", "zones 1-2": zone indices are labels, not computed numbers. */
private val ZONE_REF = Regex("""(?i)\b(?:zones?|z)\s?([1-5])(?:\s?(?:-|–|to|and)\s?([1-5]))?\b""")

/** Names that contain digits but are not numbers: VO2max, 5K / 10K races. */
private val NAMED = Regex("""(?i)\bVO2(?:\s?max)?\b|\b(?:5|10)\s?k\b""")

/** A numbered or bulleted list marker at the start of a line. */
private val LIST_MARKER = Regex("""^\s*(?:\d{1,2}[.)]|[-*•])\s+""")
private val NUMBER = Regex("""[-−]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?""")
private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

/** Facts the reply may quote: every number in the payload, plus its date and m:ss / h:mm:ss strings. */
class AllowedFacts(facts: JsonElement) {
    val numbers: List<Double>
    val tokens: Set<String>

    init {
        val nums = mutableListOf<Double>()
        val toks = mutableSetOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach(::walk)

                is JsonArray -> e.forEach(::walk)

                JsonNull -> Unit

                is JsonPrimitive -> if (e.isString) {
                    DATE.findAll(e.content).forEach { toks += it.value }
                    CLOCK.findAll(e.content).forEach { toks += it.value }
                } else {
                    e.doubleOrNull?.let(nums::add)
                }
            }
        }
        walk(facts)
        numbers = nums
        tokens = toks
    }
}

/**
 * True when [text] (a number as written in the reply) is a rounding or
 * formatting variant of a fact: same value at the reply's precision, sign
 * ignored ("TSB of 12 below zero"), thousands separators and percentages of
 * ratios ("0.85" ↔ "85%") allowed.
 */
fun matchesFact(text: String, facts: List<Double>): Boolean {
    val cleaned = text.replace(",", "").replace('−', '-')
    val value = cleaned.toDoubleOrNull() ?: return false
    val decimals = cleaned.substringAfter('.', "").length
    val tolerance = 0.5 * 10.0.pow(-decimals) + 1e-9
    return facts.any { f ->
        abs(abs(value) - abs(f)) <= tolerance || abs(abs(value) - abs(f * 100)) <= tolerance
    }
}

/** Numbers in [sentence] that are not facts. Empty means the sentence is verifiable. */
fun unverifiedNumbers(sentence: String, allowed: AllowedFacts): List<String> {
    val bad = mutableListOf<String>()
    var rest = LIST_MARKER.replace(sentence, "")
    DATE.findAll(rest).forEach { if (it.value !in allowed.tokens) bad += it.value }
    rest = DATE.replace(rest, " ")
    CLOCK.findAll(rest).forEach { if (it.value !in allowed.tokens) bad += it.value }
    rest = CLOCK.replace(rest, " ")
    rest = ZONE_REF.replace(rest, " ")
    rest = NAMED.replace(rest, " ")
    NUMBER.findAll(rest).forEach { if (!matchesFact(it.value, allowed.numbers)) bad += it.value }
    return bad
}

/**
 * Drops every sentence that states a number not present in [facts]; rejects the
 * whole reply when nothing is left or more than half of it had to go.
 */
fun checkReplyNumbers(reply: String, facts: JsonElement): CheckedReply {
    val allowed = AllowedFacts(facts)
    val removed = mutableListOf<String>()
    var total = 0
    val lines = reply.trim().lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull ""
        val kept = line.split(SENTENCE_END).filter { it.isNotBlank() }.filter { sentence ->
            total++
            val ok = unverifiedNumbers(sentence, allowed).isEmpty()
            if (!ok) removed += sentence.trim()
            ok
        }
        // A line whose every sentence was dropped disappears entirely.
        if (kept.isEmpty()) null else kept.joinToString(" ")
    }
    val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    val rejected = text.isBlank() || removed.size * 2 > total
    return CheckedReply(text = if (rejected) "" else text, removed = removed, rejected = rejected)
}
