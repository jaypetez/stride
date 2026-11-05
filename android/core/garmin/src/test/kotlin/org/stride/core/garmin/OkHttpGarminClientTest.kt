package org.stride.core.garmin

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.stride.core.common.FixedStrideClock
import org.stride.core.common.StrideResult
import org.stride.core.model.SportType

class OkHttpGarminClientTest {
    private val now = Instant.parse("2026-11-05T08:00:00Z")
    private val apiKey = "PERSONAL-KEY-should-never-leak-123"
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val sleeps = mutableListOf<Long>()
    private var tokenCounter = 0

    /** path -> queue of responses; the last one repeats. */
    private val routes = mutableMapOf<String, ArrayDeque<MockResponse>>()

    private fun route(pathPrefix: String, vararg responses: MockResponse) {
        routes[pathPrefix] = ArrayDeque(responses.toList())
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun code(c: Int, headers: Map<String, String> = emptyMap()) =
        MockResponse.Builder().code(c).body("{}").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    private fun tokenResponse(exp: Long = now.epochSecond + 86_400): MockResponse {
        tokenCounter++
        return ok(Fixtures.jwt(exp))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (path.endsWith("/token") && "token" !in routes) {
                    return tokenResponse()
                }
                val key = routes.keys.filter { path.contains(it) }.maxByOrNull { it.length } ?: return code(404)
                val queue = routes.getValue(key)
                return if (queue.size > 1) queue.removeFirst() else queue.first()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client(
        cache: TokenCache = InMemoryTokenCache(),
        key: String? = apiKey,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) =
        OkHttpGarminClient(
            http = OkHttpClient(),
            keyProvider = { key },
            tokenCache = cache,
            clock = FixedStrideClock(now),
            ioDispatcher = dispatcher,
            config = GarminConfig(baseUrl = server.url("/"), initialBackoffMs = 1_000),
            sleep = { sleeps += it }
        )

    @Test
    fun exchangeSendsApiKeyHeaderAndParsesJwtExpiry() = runTest {
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.exchangeToken()
        assertIs<StrideResult.Ok<AccessToken>>(result)
        assertEquals(now.epochSecond + 86_400, result.value.expiresAtEpochSec)
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("ApiKey $apiKey", req.headers["Authorization"])
        assertTrue(req.url.encodedPath.endsWith(OkHttpGarminClient.TOKEN_PATH))
    }

    @Test
    fun exchangeAcceptsQuotedAndJsonTokenBodies() {
        assertEquals("abc.def.ghi", Jwt.parseTokenBody("\"abc.def.ghi\"\n"))
        assertEquals("abc.def.ghi", Jwt.parseTokenBody("""{"access_token":"abc.def.ghi"}"""))
        assertEquals(1_793_000_000L, Jwt.expiry(Fixtures.jwt(1_793_000_000L)))
    }

    @Test
    fun missingKeyIsTyped() = runTest {
        val c = client(key = null, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(StrideResult.Err(GarminError.KeyMissing), c.exchangeToken())
    }

    @Test
    fun invalidKeyOnExchangeIsKeyInvalid() = runTest {
        route("token", code(401))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(StrideResult.Err(GarminError.KeyInvalid), c.exchangeToken(apiKey = "bad"))
    }

    @Test
    fun activitiesAreWindowedToTwentyEightDaysAndPaged() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, ok(Fixtures.text("activities.json")))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-11-04"))
        assertIs<StrideResult.Ok<*>>(result)
        val searches = requests.filter { it.url.encodedPath.contains("activities/search") }
        // 96 days → 4 windows of ≤ 28 days; each window returns 6 (< pageSize 20) so one page each.
        assertEquals(4, searches.size)
        searches.forEach { r ->
            val start = LocalDate.parse(r.url.queryParameter("startDate"))
            val end = LocalDate.parse(r.url.queryParameter("endDate"))
            assertTrue(java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 27, "window too large: $start..$end")
            assertEquals("20", r.url.queryParameter("limit"))
            assertTrue(r.headers["Authorization"]!!.startsWith("Bearer "))
        }
        // Newest window first.
        assertEquals("2026-11-04", searches.first().url.queryParameter("endDate"))
        // Same 6 activities in every window are de-duplicated.
        @Suppress("UNCHECKED_CAST")
        val activities = (result as StrideResult.Ok<List<org.stride.core.model.Activity>>).value
        assertEquals(6, activities.size)
        assertEquals(1, tokenCounter, "token is cached across calls")
        assertTrue(activities.any { it.sport == SportType.RUN })
        assertTrue(activities.any { it.sport == SportType.CYCLE })
        assertTrue(activities.zipWithNext().all { (a, b) -> a.startEpochSec >= b.startEpochSec })
    }

    @Test
    fun fullPagesRequestTheNextPage() = runTest {
        val page = Fixtures.text("activities.json")
        route(OkHttpGarminClient.ACTIVITIES_PATH, ok(page), ok("[]"))
        val c = OkHttpGarminClient(
            http = OkHttpClient(),
            keyProvider = { apiKey },
            tokenCache = InMemoryTokenCache(),
            clock = FixedStrideClock(now),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            config = GarminConfig(baseUrl = server.url("/"), pageSize = 6),
            sleep = { sleeps += it }
        )
        c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        val offsets = requests.filter { it.url.encodedPath.contains("search") }.map { it.url.queryParameter("start") }
        assertEquals(listOf("0", "6"), offsets)
    }

    @Test
    fun unauthorizedDataCallReExchangesOnceThenSucceeds() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(401), ok("[]"))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertIs<StrideResult.Ok<*>>(result)
        assertEquals(2, tokenCounter)
    }

    @Test
    fun revokedKeySurfacesKeyInvalid() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(401))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertEquals(StrideResult.Err(GarminError.KeyInvalid), result)
    }

    @Test
    fun expiredCachedTokenIsReExchanged() = runTest {
        val cache = InMemoryTokenCache()
        cache.put(AccessToken("old.token.x", expiresAtEpochSec = now.epochSecond + 60))
        route(OkHttpGarminClient.ACTIVITIES_PATH, ok("[]"))
        val c = client(cache = cache, dispatcher = StandardTestDispatcher(testScheduler))
        c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertEquals(1, tokenCounter)
        assertFalse(requests.any { it.headers["Authorization"] == "Bearer old.token.x" })
    }

    @Test
    fun rateLimitAndServerErrorsBackOffExponentiallyThenGiveUp() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(429, mapOf("Retry-After" to "2")), code(503), code(500), code(500))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertEquals(StrideResult.Err(GarminError.Server(500)), result)
        // 3 retries: max(Retry-After 2s, 1s), then 2s, then 4s.
        assertEquals(listOf(2_000L, 2_000L, 4_000L), sleeps)
    }

    @Test
    fun rateLimitRecoversWithinRetryBudget() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(429), ok("[]"))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        assertIs<StrideResult.Ok<*>>(c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04")))
        assertEquals(1, sleeps.size)
    }

    @Test
    fun otherHttpErrorsAreTypedAndNotRetried() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(400))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(StrideResult.Err(GarminError.Http(400)), c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun wellnessMergesHealthEndpointsPerDay() = runTest {
        route(OkHttpGarminClient.DAILIES_PATH, ok(Fixtures.text("dailies.json")))
        route(OkHttpGarminClient.SLEEP_PATH, ok(Fixtures.text("sleeps.json")))
        route(OkHttpGarminClient.HRV_PATH, ok(Fixtures.text("hrv.json")))
        route(OkHttpGarminClient.STRESS_PATH, ok(Fixtures.text("stress.json")))
        route(OkHttpGarminClient.WEIGHT_PATH, ok(Fixtures.text("body-comps.json")))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.wellness(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-04"))
        assertIs<StrideResult.Ok<List<org.stride.core.model.DailyWellness>>>(result)
        val day = result.value.first { it.date == "2026-10-30" }
        assertEquals(48, day.restingHr)
        assertEquals(61.0, day.hrvLastNightMs)
        assertEquals("BALANCED", day.hrvStatus)
        assertEquals(25_200, day.sleepSec)
        assertEquals(72, day.sleepScore)
        assertEquals(27, day.stressAvg)
        val weighed = result.value.first { it.date == "2026-11-04" }
        assertEquals(70.2, weighed.weightKg!!, 1e-9)
        assertEquals(result.value.map { it.date }.sorted(), result.value.map { it.date })
    }

    @Test
    fun hrvNoContentIsEmptyNotAnError() = runTest {
        route(OkHttpGarminClient.DAILIES_PATH, ok("""{"data":[]}"""))
        route(OkHttpGarminClient.SLEEP_PATH, ok("[]"))
        route(OkHttpGarminClient.HRV_PATH, MockResponse.Builder().code(204).build())
        route(OkHttpGarminClient.STRESS_PATH, ok("[]"))
        route(OkHttpGarminClient.WEIGHT_PATH, ok("{}"))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(StrideResult.Ok(emptyList()), c.wellness(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04")))
    }

    @Test
    fun fitDownloadIsUnzipped() = runTest {
        val fit = byteArrayOf(14, 0x20, 0, 0, 0, 0, 0, 0, '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 1, 2)
        val zipped = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("12345_ACTIVITY.fit"))
                z.write(fit)
                z.closeEntry()
            }
        }.toByteArray()
        route(OkHttpGarminClient.FIT_PATH, MockResponse.Builder().code(200).body(Buffer().write(zipped)).build())
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.fitFile(12345)
        assertIs<StrideResult.Ok<ByteArray>>(result)
        assertContentEquals(fit, result.value)
        assertEquals("application/x-zip-compressed", requests.last().headers["Accept"])
    }

    /** AUD-026: a Retry-After far beyond the cap returns RateLimited at once instead of sleeping for an hour. */
    @Test
    fun hugeRetryAfterGivesUpWithoutSleeping() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(429, mapOf("Retry-After" to "3600")))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertEquals(StrideResult.Err(GarminError.RateLimited(3600)), result)
        assertTrue(sleeps.isEmpty(), "must not sleep for Retry-After 3600 s, slept $sleeps")
    }

    @Test
    fun retrySleepsNeverExceedTheCap() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, code(500))
        val c = OkHttpGarminClient(
            http = OkHttpClient(),
            keyProvider = { apiKey },
            tokenCache = InMemoryTokenCache(),
            clock = FixedStrideClock(now),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            config = GarminConfig(baseUrl = server.url("/"), maxRetries = 6, initialBackoffMs = 1_000, maxBackoffMs = 5_000),
            sleep = { sleeps += it }
        )
        c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L), sleeps)
    }

    /** AUD-028 / AUD-029: bad items are skipped, good ones kept, and the recorded offset becomes the zone. */
    @Test
    fun malformedActivitiesAreSkippedAndLocalOffsetIsKept() = runTest {
        route(OkHttpGarminClient.ACTIVITIES_PATH, ok(Fixtures.text("activities-malformed.json")))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04"))
        assertIs<StrideResult.Ok<List<org.stride.core.model.Activity>>>(result)
        val byId = result.value.associateBy { it.id }
        assertEquals(setOf(910000001L, 910000006L), byId.keys)
        val abroad = byId.getValue(910000001L)
        assertEquals(Instant.parse("2026-11-03T22:05:00Z").epochSecond, abroad.startEpochSec)
        assertEquals("+09:00", abroad.timeZone)
        val iso = byId.getValue(910000006L)
        assertEquals(Instant.parse("2026-11-02T08:05:00Z").epochSecond, iso.startEpochSec)
        assertEquals("+01:00", iso.timeZone)
        assertTrue(result.value.none { it.startEpochSec <= 0 }, "no 1970 runs")
    }

    @Test
    fun activityDetailMapsSummaryStartAndIanaZone() = runTest {
        route(OkHttpGarminClient.DETAIL_PATH, ok(Fixtures.text("activity-detail.json")))
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        val result = c.activityDetail(900000006)
        assertIs<StrideResult.Ok<org.stride.core.model.Activity>>(result)
        assertEquals("Europe/Berlin", result.value.timeZone)
        assertEquals(Instant.parse("2026-11-04T06:05:00Z").epochSecond, result.value.startEpochSec)
    }

    @Test
    fun corruptOrTruncatedZipIsParseError() = runTest {
        val zipped = zip("1_ACTIVITY.fit", ByteArray(4096) { (it % 7).toByte() })
        val corrupt = zipped.copyOf().also { bytes -> for (i in 30 until 60) bytes[i] = 0x7F }
        route(OkHttpGarminClient.FIT_PATH, MockResponse.Builder().code(200).body(Buffer().write(corrupt)).build())
        val c = client(dispatcher = StandardTestDispatcher(testScheduler))
        assertIs<GarminError.Parse>((c.fitFile(1) as StrideResult.Err).error)

        listOf(zipped.copyOf(zipped.size / 2), zipped.copyOf(10), byteArrayOf(0x50, 0x4B, 3, 4)).forEach { bad ->
            assertIs<StrideResult.Err<GarminError>>(OkHttpGarminClient.unzipFit(bad))
        }
        assertEquals(StrideResult.Err(GarminError.Parse("download contained no .fit file")), OkHttpGarminClient.unzipFit(zip("readme.txt", byteArrayOf(1))))
    }

    /** AUD-028: a zip bomb is rejected once it inflates past the cap, without inflating it all. */
    @Test
    fun inflatedFitAboveCapIsRejected() {
        val bomb = zip("bomb.fit", ByteArray(2 * 1024 * 1024))
        assertTrue(bomb.size < 16 * 1024, "zeros compress well: ${bomb.size}")
        assertEquals(StrideResult.Err(GarminError.Parse("FIT file too large")), OkHttpGarminClient.unzipFit(bomb, maxBytes = 1024 * 1024))
        assertIs<StrideResult.Ok<ByteArray>>(OkHttpGarminClient.unzipFit(bomb, maxBytes = 2 * 1024 * 1024))
        assertEquals(50L * 1024 * 1024, OkHttpGarminClient.MAX_FIT_BYTES)
    }

    private fun zip(name: String, content: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(name))
            z.write(content)
            z.closeEntry()
        }
    }.toByteArray()

    @Test
    fun keyAndTokenNeverAppearInErrorsLogsOrToString() = runTest {
        val logged = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged += record.message.orEmpty()
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val root = Logger.getLogger("")
        root.addHandler(handler)
        root.level = Level.ALL
        try {
            route(OkHttpGarminClient.ACTIVITIES_PATH, code(401))
            val c = client(dispatcher = StandardTestDispatcher(testScheduler))
            val token = (c.exchangeToken() as StrideResult.Ok).value
            val results = listOf(
                c.activities(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")),
                c.exchangeToken(apiKey = "x")
            )
            val rendered = results.joinToString() + token.toString() + logged.joinToString()
            assertFalse(rendered.contains(apiKey))
            assertFalse(rendered.contains(token.value))
        } finally {
            root.removeHandler(handler)
        }
    }
}
