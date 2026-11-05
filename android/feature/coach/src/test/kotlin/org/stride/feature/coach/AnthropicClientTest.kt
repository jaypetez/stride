package org.stride.feature.coach

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
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

    /** AUD-037: a retired model id answers 404 (or a 400 naming the model); show a clear message, not "HTTP 404". */
    @Test
    fun retiredModelIsReportedAsUnavailable() = runTest {
        reply(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-haiku-5-5"}}""")
        assertEquals(CoachLlmResult.Err(CoachLlmError.ModelUnavailable), client.complete("k", "s", "u"))
        reply(400, """{"type":"error","error":{"type":"invalid_request_error","message":"model: unknown model"}}""")
        assertEquals(CoachLlmResult.Err(CoachLlmError.ModelUnavailable), client.complete("k", "s", "u"))
        reply(400, """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens too large"}}""")
        assertEquals(CoachLlmResult.Err(CoachLlmError.Failed("HTTP 400")), client.complete("k", "s", "u"))
        assertEquals(R.string.coach_error_model_unavailable, CoachLlmError.ModelUnavailable.message.res)
    }

    /** AUD-036: cancelling the coach request (leaving the screen) cancels the HTTP call instead of blocking a thread. */
    @Test
    fun cancellingTheRequestCancelsTheCall() = runBlocking {
        val canceled = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.complete(Unit)
                    }
                }
            )
            .build()
        val slow = OkHttpAnthropicClient(http, Dispatchers.IO, baseUrl = server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(30, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.IO) { slow.complete("k", "s", "u") }
        server.takeRequest() // the request reached the server
        job.cancelAndJoin()
        withTimeout(5_000) { canceled.await() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun networkFailureIsReported() = runTest {
        server.close()
        assertEquals(CoachLlmResult.Err(CoachLlmError.Network), client.complete("k", "s", "u"))
    }
}
