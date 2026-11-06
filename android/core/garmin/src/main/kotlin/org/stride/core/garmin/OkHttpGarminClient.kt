package org.stride.core.garmin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideLog
import org.stride.core.common.StrideResult
import org.stride.core.garmin.dto.ActivityDetailDto
import org.stride.core.garmin.dto.ActivityDto
import org.stride.core.garmin.dto.DailiesEnvelope
import org.stride.core.garmin.dto.HrvEnvelope
import org.stride.core.garmin.dto.SleepDto
import org.stride.core.garmin.dto.StressDayDto
import org.stride.core.garmin.dto.WeightEnvelope
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness

/**
 * [GarminClient] on OkHttp. Never logs, and never puts the key or token into an
 * error, exception message or `toString()` (MVP-003).
 */
class OkHttpGarminClient(
    private val http: OkHttpClient,
    private val keyProvider: GarminKeyProvider,
    private val tokenCache: TokenCache,
    private val clock: StrideClock,
    private val ioDispatcher: CoroutineDispatcher,
    private val config: GarminConfig = GarminConfig(),
    /** Injectable so tests don't actually wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) : GarminClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val tokenMutex = Mutex()

    override suspend fun exchangeToken(apiKey: String?): GarminResult<AccessToken> {
        val key = apiKey ?: keyProvider.apiKey() ?: return StrideResult.Err(GarminError.KeyMissing)
        val request = Request.Builder()
            .url(url(TOKEN_PATH))
            .header("Authorization", "ApiKey $key")
            // The token endpoint returns the raw JWT as text and answers 406 Not Acceptable to
            // `Accept: application/json` (docs/garmin/personal-api.md, "Token exchange").
            .header("Accept", TOKEN_ACCEPT)
            .header("User-Agent", config.userAgent)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return when (val result = execute(request, isTokenCall = true)) {
            is StrideResult.Err -> result

            is StrideResult.Ok -> {
                val raw = Jwt.parseTokenBody(result.value.decodeToString())
                    ?: return StrideResult.Err(GarminError.Parse("empty token response"))
                val exp = Jwt.expiry(raw) ?: (clock.now().epochSecond + DEFAULT_TOKEN_TTL_SEC)
                val token = AccessToken(raw, exp)
                if (apiKey == null) tokenCache.put(token)
                StrideResult.Ok(token)
            }
        }
    }

    override suspend fun clearToken() = tokenCache.clear()

    override suspend fun activities(start: LocalDate, end: LocalDate): GarminResult<List<Activity>> {
        val all = mutableListOf<Activity>()
        for (window in dateWindows(start, end, config.maxWindowDays)) {
            var offset = 0
            while (true) {
                val url = url(ACTIVITIES_PATH).newBuilder()
                    .addQueryParameter("startDate", window.start.toString())
                    .addQueryParameter("endDate", window.end.toString())
                    .addQueryParameter("start", offset.toString())
                    .addQueryParameter("limit", config.pageSize.toString())
                    .build()
                // Decoded item by item: one malformed activity is skipped, not the whole page (AUD-028).
                val page = when (val r = getJson(url, ListSerializer(JsonElement.serializer()))) {
                    is StrideResult.Err -> return r
                    is StrideResult.Ok -> r.value ?: emptyList()
                }
                all += page.mapNotNull(::decodeActivity)
                if (page.size < config.pageSize) break
                offset += config.pageSize
            }
        }
        return StrideResult.Ok(all.distinctBy { it.id }.sortedByDescending { it.startEpochSec })
    }

    override suspend fun activityDetail(activityId: Long): GarminResult<Activity> {
        val detail = when (val r = getJson(url("$DETAIL_PATH/$activityId"), ActivityDetailDto.serializer())) {
            is StrideResult.Err -> return r
            is StrideResult.Ok -> r.value ?: return StrideResult.Err(GarminError.Http(204))
        }
        // The detail endpoint nests summary fields differently; reuse the list shape for the numbers.
        val dto = ActivityDto(
            activityId = detail.activityId,
            activityName = detail.activityName,
            activityType = detail.activityTypeDTO,
            startTimeGMT = detail.summaryDTO?.startTimeGMT,
            startTimeLocal = detail.summaryDTO?.startTimeLocal
        )
        return dto.toActivityOrNull(timeZone = detail.timeZoneUnitDTO?.timeZone)?.let { StrideResult.Ok(it) }
            ?: StrideResult.Err(GarminError.Parse("activity has no usable start time"))
    }

    override suspend fun fitFile(activityId: Long): GarminResult<ByteArray> {
        val request = authorized(url("$FIT_PATH/$activityId"), accept = "application/x-zip-compressed")
            ?: return StrideResult.Err(GarminError.KeyMissing)
        return when (val r = executeAuthorized(request)) {
            is StrideResult.Err -> r
            is StrideResult.Ok -> unzipFit(r.value)
        }
    }

    @Suppress("ReturnCount") // each endpoint short-circuits on its typed error
    override suspend fun wellness(start: LocalDate, end: LocalDate): GarminResult<List<DailyWellness>> {
        val dailies = mutableListOf<org.stride.core.garmin.dto.DailySummaryDto>()
        val sleeps = mutableListOf<SleepDto>()
        val hrv = mutableListOf<org.stride.core.garmin.dto.HrvSummaryDto>()
        val stress = mutableListOf<StressDayDto>()
        val weights = mutableListOf<org.stride.core.garmin.dto.WeightDayDto>()
        for (w in dateWindows(start, end, config.maxWindowDays)) {
            val s = w.start.toString()
            val e = w.end.toString()
            fun ranged(path: String) = url(path).newBuilder().addQueryParameter("startDate", s).addQueryParameter("endDate", e).build()

            // Wellness is optional enrichment. One endpoint failing (a device that doesn't record it,
            // an unconfirmed response shape, a 4xx/5xx on a range) must not abort the whole sync and
            // lose the runs' fitness metrics. Auth, throttling and connectivity errors still stop it.
            fun <T> optional(name: String, r: GarminResult<T?>, add: (T?) -> Unit): GarminError? = when (r) {
                is StrideResult.Ok -> {
                    add(r.value)
                    null
                }

                is StrideResult.Err -> if (r.error.stopsSync()) {
                    r.error
                } else {
                    StrideLog.w(TAG, "skipped optional wellness '$name' for $s..$e: ${r.error}")
                    null
                }
            }

            optional("daily summaries", getJson(ranged(DAILIES_PATH), DailiesEnvelope.serializer())) {
                dailies += it?.data.orEmpty()
            }?.let { return StrideResult.Err(it) }
            optional("sleep", getJson(ranged(SLEEP_PATH), ListSerializer(SleepDto.serializer()))) {
                sleeps += it.orEmpty()
            }?.let { return StrideResult.Err(it) }
            optional("hrv", getJson(url("$HRV_PATH/$s/$e"), HrvEnvelope.serializer())) {
                hrv += it?.hrvSummaries.orEmpty()
            }?.let { return StrideResult.Err(it) }
            optional("stress", getJson(url("$STRESS_PATH/$s/$e"), ListSerializer(StressDayDto.serializer()))) {
                stress += it.orEmpty()
            }?.let { return StrideResult.Err(it) }
            val weightUrl = url("$WEIGHT_PATH/$s/$e").newBuilder().addQueryParameter("includeAll", "false").build()
            optional("weight", getJson(weightUrl, WeightEnvelope.serializer())) {
                weights += it?.dailyWeightSummaries.orEmpty()
            }?.let { return StrideResult.Err(it) }
        }
        return StrideResult.Ok(mergeWellness(dailies, sleeps, hrv, stress, weights))
    }

    // Plumbing

    private fun url(path: String): HttpUrl = config.baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()

    /** Errors that must stop a sync rather than be skipped: the key, throttling or connectivity. */
    private fun GarminError.stopsSync(): Boolean =
        this == GarminError.KeyInvalid || this == GarminError.KeyMissing || this is GarminError.RateLimited || this is GarminError.Network

    /** GET a JSON body; 204/empty → Ok(null). */
    private suspend fun <T> getJson(url: HttpUrl, strategy: DeserializationStrategy<T>): GarminResult<T?> {
        val request = authorized(url, accept = "application/json") ?: return StrideResult.Err(GarminError.KeyMissing)
        return when (val r = executeAuthorized(request)) {
            is StrideResult.Err -> r

            is StrideResult.Ok -> {
                val text = r.value.decodeToString()
                if (text.isBlank()) {
                    StrideResult.Ok(null)
                } else {
                    try {
                        StrideResult.Ok(json.decodeFromString(strategy, text))
                    } catch (e: IllegalArgumentException) {
                        // Include the decoder's message (field/path, truncated) so logcat says what didn't match.
                        val detail = e.message?.lineSequence()?.firstOrNull()?.take(PARSE_DETAIL_MAX)
                        StrideResult.Err(GarminError.Parse("${e::class.simpleName ?: "decode error"} at ${url.encodedPath}: $detail"))
                    }
                }
            }
        }
    }

    /** One list item to [Activity], or null (logged) when it is malformed or has no usable start time. */
    private fun decodeActivity(element: JsonElement): Activity? {
        val dto = try {
            json.decodeFromJsonElement(ActivityDto.serializer(), element)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            StrideLog.w(TAG, "skipped a malformed activity", e)
            return null
        }
        return dto.toActivityOrNull() ?: run {
            StrideLog.w(TAG, "skipped activity ${dto.activityId}: no usable start time")
            null
        }
    }

    private fun authorized(url: HttpUrl, accept: String): Request? =
        Request.Builder().url(url).header("Accept", accept).header("User-Agent", config.userAgent).get().build()

    /** Adds the bearer token; on 401 re-exchanges the key once, then reports KeyInvalid. */
    private suspend fun executeAuthorized(base: Request): GarminResult<ByteArray> {
        var reExchanged = false
        while (true) {
            val token = when (val t = validToken(forceRefresh = reExchanged)) {
                is StrideResult.Err -> return t
                is StrideResult.Ok -> t.value
            }
            val request = base.newBuilder().header("Authorization", "Bearer ${token.value}").build()
            val result = execute(request, isTokenCall = false)
            if (result is StrideResult.Err && result.error == GarminError.KeyInvalid && !reExchanged) {
                tokenCache.clear()
                reExchanged = true
                continue
            }
            return result
        }
    }

    private suspend fun validToken(forceRefresh: Boolean): GarminResult<AccessToken> = tokenMutex.withLock {
        val cached = tokenCache.get()
        val now = clock.now().epochSecond
        if (!forceRefresh && cached != null && cached.expiresAtEpochSec - config.tokenRefreshMarginSec > now) {
            return StrideResult.Ok(cached)
        }
        exchangeToken(apiKey = null)
    }

    /** Executes with bounded exponential backoff on 408/429/5xx and I/O errors. */
    private suspend fun execute(request: Request, isTokenCall: Boolean): GarminResult<ByteArray> {
        var attempt = 0
        var backoff = config.initialBackoffMs
        while (true) {
            val outcome: GarminResult<ByteArray> = try {
                withContext(ioDispatcher) {
                    http.newCall(request).await().use { response -> classify(response, isTokenCall) }
                }
            } catch (e: IOException) {
                // Only the exception type: OkHttp messages can echo URLs, never headers, but keep it minimal.
                StrideResult.Err(GarminError.Network(e::class.simpleName ?: "IOException"))
            }
            val retryAfter = (outcome as? StrideResult.Err)?.error?.let(::retryDelayMs)
            if (retryAfter == null || attempt >= config.maxRetries) return outcome
            // A Retry-After beyond our cap (e.g. 3600 s) would park the worker for an hour:
            // give up now with RateLimited and let WorkManager's backoff reschedule (AUD-026).
            if (retryAfter > config.maxBackoffMs) return outcome
            sleep(min(maxOf(retryAfter, backoff), config.maxBackoffMs))
            backoff = min(backoff * 2, config.maxBackoffMs)
            attempt++
        }
    }

    private fun retryDelayMs(error: GarminError): Long? = when (error) {
        is GarminError.RateLimited -> (error.retryAfterSec ?: 0).coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        is GarminError.Server -> 0
        is GarminError.Network -> 0
        is GarminError.Http -> if (error.code == 408) 0 else null
        else -> null
    }

    private fun classify(response: Response, isTokenCall: Boolean): GarminResult<ByteArray> {
        val code = response.code
        return when {
            code == 204 -> StrideResult.Ok(ByteArray(0))
            response.isSuccessful -> StrideResult.Ok(response.body.bytes())
            code == 401 || (isTokenCall && code == 403) -> StrideResult.Err(GarminError.KeyInvalid)
            code == 429 -> StrideResult.Err(GarminError.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull()))
            code >= 500 -> StrideResult.Err(GarminError.Server(code))
            else -> StrideResult.Err(GarminError.Http(code))
        }
    }

    companion object {
        const val TOKEN_PATH = "personalapitoken-service/personal-api/personal-api-key/token"
        const val TOKEN_ACCEPT = "*/*"
        private const val PARSE_DETAIL_MAX = 160
        const val ACTIVITIES_PATH = "activitylist-service/activities/search/activities"
        const val DETAIL_PATH = "activity-service/activity"
        const val FIT_PATH = "download-service/files/activity"
        const val DAILIES_PATH = "usersummary-service/v2/dailysummaries"
        const val SLEEP_PATH = "sleep-service/sleep/dailySleepSummariesByDate"
        const val HRV_PATH = "hrv-service/hrv/daily"
        const val STRESS_PATH = "usersummary-service/stats/stress/daily"
        const val WEIGHT_PATH = "weight-service/weight/range"
        private const val DEFAULT_TOKEN_TTL_SEC = 24L * 3600
        private const val TAG = "GarminClient"
        private const val COPY_BUFFER = 8 * 1024

        /** Largest inflated FIT file accepted; a multi-hour run is a few MB, so anything bigger is hostile (zip bomb). */
        const val MAX_FIT_BYTES: Long = 50L * 1024 * 1024

        /**
         * Returns the first `.fit` entry of a ZIP, or the bytes themselves if they already are a FIT file.
         * Corrupt or truncated archives and entries inflating past [maxBytes] are [GarminError.Parse], never thrown (AUD-028).
         */
        fun unzipFit(bytes: ByteArray, maxBytes: Long = MAX_FIT_BYTES): GarminResult<ByteArray> {
            if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") {
                return if (bytes.size > maxBytes) StrideResult.Err(GarminError.Parse("FIT file too large")) else StrideResult.Ok(bytes)
            }
            return try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> firstFitEntry(zip, maxBytes) }
            } catch (e: IOException) {
                // ZipException (bad header / CRC) and EOFException (truncated) are both IOExceptions.
                StrideResult.Err(GarminError.Parse(e::class.simpleName ?: "zip error"))
            } catch (e: IllegalArgumentException) {
                // Malformed entry names (bad UTF-8) surface as IllegalArgumentException.
                StrideResult.Err(GarminError.Parse(e::class.simpleName ?: "zip error"))
            }
        }

        private fun firstFitEntry(zip: ZipInputStream, maxBytes: Long): GarminResult<ByteArray> {
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".fit", ignoreCase = true)) {
                    return readCapped(zip, maxBytes)?.let { StrideResult.Ok(it) }
                        ?: StrideResult.Err(GarminError.Parse("FIT file too large"))
                }
                entry = zip.nextEntry
            }
            return StrideResult.Err(GarminError.Parse("download contained no .fit file"))
        }

        /** Reads at most [maxBytes]; null if the stream holds more. */
        private fun readCapped(zip: ZipInputStream, maxBytes: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER)
            var total = 0L
            var n = zip.read(buffer)
            while (n >= 0) {
                total += n
                if (total > maxBytes) return null
                out.write(buffer, 0, n)
                n = zip.read(buffer)
            }
            return out.toByteArray()
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)

            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        }
    )
    cont.invokeOnCancellation { runCatching { cancel() } }
}
