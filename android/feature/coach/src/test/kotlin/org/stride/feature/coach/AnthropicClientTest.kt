package org.stride.feature.coach

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.stride.core.model.UnitSystem

/** The raw Messages API call: headers, body shape and error mapping (MVP-019). */
class AnthropicClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpAnthropicClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpAnthropicClient(OkHttpClient(), Dispatchers.IO, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.close()

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun sendsFactsOnlyRequestWithRequiredHeaders() = runTest {
        val content = """[{"type":"text","text":"Steady week."}]"""
        reply(200, """{"id":"msg_1","type":"message","role":"assistant","content":$content,"stop_reason":"end_turn"}""")
        val facts = buildActivityFacts(Samples.detail, UnitSystem.METRIC, java.time.ZoneOffset.UTC)

        val result = client.complete("sk-ant-test", COACH_SYSTEM_PROMPT, coachUserPrompt(facts))

        assertEquals(CoachLlmResult.Ok("Steady week."), result)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertTrue(request.headers["content-type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(OkHttpAnthropicClient.DEFAULT_MODEL, body.getValue("model").jsonPrimitive.content)
        assertEquals(OkHttpAnthropicClient.MAX_TOKENS.toString(), body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(COACH_SYSTEM_PROMPT, body.getValue("system").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val sent = messages.single().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(sent.contains(facts.toJson()))
        listOf("streams", "laps", "time_sec", "heart_rate", "Morning Run").forEach { assertFalse(sent.contains(it), it) }
    }

    @Test
    fun skipsThinkingBlocks() = runTest {
        reply(200, """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hello"}],"stop_reason":"end_turn"}""")
        assertEquals(CoachLlmResult.Ok("Hello"), client.complete("k", "s", "u"))
    }

    @Test
    fun mapsErrors() = runTest {
        reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(CoachLlmResult.Err(CoachLlmError.InvalidKey), client.complete("k", "s", "u"))
        reply(429, "{}")
        assertEquals(CoachLlmResult.Err(CoachLlmError.RateLimited), client.complete("k", "s", "u"))
        reply(529, "{}")
        assertEquals(CoachLlmResult.Err(CoachLlmError.Overloaded), client.complete("k", "s", "u"))
        reply(200, """{"content":[],"stop_reason":"refusal"}""")
        assertEquals(CoachLlmResult.Err(CoachLlmError.Refused), client.complete("k", "s", "u"))
    }

    @Test
    fun networkFailureIsReported() = runTest {
        server.close()
        assertEquals(CoachLlmResult.Err(CoachLlmError.Network), client.complete("k", "s", "u"))
    }
}
