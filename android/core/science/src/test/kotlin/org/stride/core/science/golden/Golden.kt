package org.stride.core.science.golden

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/** JSON codec for goldens: strict on inputs (unknown keys fail, so the Kotlin model must be complete), omits nulls on output. */
val goldenJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

/** One `{ name, fn, input, output }` case from contracts/science-golden. */
data class GoldenCase(val file: String, val name: String, val fn: String, val input: JsonObject, val output: JsonElement)

/** Loads a golden file from the directory given by the `stride.goldenDir` system property. */
fun loadGolden(file: String): List<GoldenCase> {
    val dir = System.getProperty("stride.goldenDir") ?: fail("stride.goldenDir system property is not set")
    val root = goldenJson.parseToJsonElement(File(dir, file).readText()).jsonObject
    return root.getValue("cases").jsonArray.map {
        val c = it.jsonObject
        GoldenCase(
            file = file,
            name = c.getValue("name").jsonPrimitive.content,
            fn = c.getValue("fn").jsonPrimitive.content,
            input = c.getValue("input").jsonObject,
            output = c["output"] ?: JsonNull
        )
    }
}

/** Decodes input parameter [key] (absent = JSON null). */
inline fun <reified T> GoldenCase.arg(key: String): T = goldenJson.decodeFromJsonElement(serializer<T>(), input[key] ?: JsonNull)

/** Encodes a Kotlin result for comparison. */
inline fun <reified T> encode(value: T): JsonElement = goldenJson.encodeToJsonElement(serializer<T>(), value)

/** Pairs a golden fn name with its Kotlin call (gives the lambda its parameter type). */
infix fun String.runs(call: (GoldenCase) -> JsonElement): Pair<String, (GoldenCase) -> JsonElement> = this to call

/**
 * Recursive comparison: strings and booleans exactly, numbers within 1e-6 relative (or 1e-9 absolute near 0),
 * and an absent key equals JSON null on either side. Returns mismatch descriptions (empty when equal).
 */
object GoldenAssert {
    const val RELATIVE_TOLERANCE = 1e-6
    const val ABSOLUTE_TOLERANCE = 1e-9

    fun diff(expected: JsonElement, actual: JsonElement, path: String = "$"): List<String> {
        val e = expected
        val a = actual
        return when {
            e is JsonNull || a is JsonNull -> if (e is JsonNull && a is JsonNull) emptyList() else listOf("$path: expected $e, got $a")

            e is JsonObject && a is JsonObject -> (e.keys + a.keys).sorted().flatMap { k ->
                diff(e[k] ?: JsonNull, a[k] ?: JsonNull, "$path.$k")
            }

            e is JsonArray && a is JsonArray ->
                if (e.size != a.size) {
                    listOf("$path: expected ${e.size} items, got ${a.size}")
                } else {
                    e.indices.flatMap { diff(e[it], a[it], "$path[$it]") }
                }

            e is JsonPrimitive && a is JsonPrimitive -> primitiveDiff(e, a, path)

            else -> listOf("$path: expected $e, got $a")
        }
    }

    private fun primitiveDiff(e: JsonPrimitive, a: JsonPrimitive, path: String): List<String> {
        if (e.isString || a.isString) {
            return if (e.isString && a.isString && e.content == a.content) emptyList() else listOf("$path: expected $e, got $a")
        }
        if (e.booleanOrNull != null || a.booleanOrNull != null) {
            return if (e.booleanOrNull == a.booleanOrNull) emptyList() else listOf("$path: expected $e, got $a")
        }
        val ev = e.double
        val av = a.double
        val close = abs(ev - av) <= ABSOLUTE_TOLERANCE || abs(ev - av) <= RELATIVE_TOLERANCE * max(abs(ev), abs(av))
        return if (close) emptyList() else listOf("$path: expected $ev, got $av")
    }

    fun assertMatches(expected: JsonElement, actual: JsonElement, label: String) {
        val mismatches = diff(expected, actual)
        assertTrue(mismatches.isEmpty(), "$label\n" + mismatches.take(20).joinToString("\n"))
    }
}

/**
 * Runs every case in [file] through [handlers] (fn name -> Kotlin call returning JSON). Fails on a fn with no
 * handler (so new goldens are never silently skipped), on a handler with no cases, and on any mismatch; all
 * failures are reported together. Returns the number of cases checked.
 */
fun runGoldenFile(file: String, handlers: Map<String, (GoldenCase) -> JsonElement>): Int {
    val cases = loadGolden(file)
    assertTrue(cases.isNotEmpty(), "$file has no cases")
    val unknown = cases.map { it.fn }.toSet() - handlers.keys
    assertTrue(unknown.isEmpty(), "$file has golden functions without a Kotlin handler: $unknown")
    val unused = handlers.keys - cases.map { it.fn }.toSet()
    assertTrue(unused.isEmpty(), "$file handlers have no golden cases: $unused")
    val failures = cases.mapNotNull { c ->
        val label = "${c.file} / ${c.fn} / ${c.name}"
        val actual = try {
            handlers.getValue(c.fn)(c)
        } catch (e: Exception) {
            return@mapNotNull "$label: threw $e"
        }
        GoldenAssert.diff(c.output, actual).takeIf { it.isNotEmpty() }?.let { "$label\n  " + it.take(10).joinToString("\n  ") }
    }
    assertTrue(failures.isEmpty(), "${failures.size}/${cases.size} golden cases failed:\n" + failures.joinToString("\n"))
    return cases.size
}
