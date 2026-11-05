package org.stride.core.garmin

import java.io.ByteArrayInputStream
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.stride.core.common.StrideClock
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
            .header("Accept", "application/json")
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
                val page = when (val r = getJson(url, ListSerializer(ActivityDto.serializer()))) {
                    is StrideResult.Err -> return r
                    is StrideResult.Ok -> r.value ?: emptyList()
                }
                all += page.map { it.toActivity() }
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
        val dto = ActivityDto(activityId = detail.activityId, activityName = detail.activityName, activityType = detail.activityTypeDTO)
        return StrideResult.Ok(dto.toActivity(timeZone = detail.timeZoneUnitDTO?.timeZone))
    }

    override suspend fun fitFile(activityId: Long): GarminResult<ByteArray> {
        val request = authorized(url("$FIT_PATH/$activityId"), accept = "application/x-zip-compressed")
            ?: return StrideResult.Err(GarminError.KeyMissing)
        return when (val r = executeAuthorized(request)) {
            is StrideResult.Err -> r

            is StrideResult.Ok -> unzipFit(r.value)?.let { StrideResult.Ok(it) }
                ?: StrideResult.Err(GarminError.Parse("download contained no .fit file"))
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
            when (val r = getJson(ranged(DAILIES_PATH), DailiesEnvelope.serializer())) {
                is StrideResult.Err -> return r
                is StrideResult.Ok -> dailies += r.value?.data.orEmpty()
            }
            when (val r = getJson(ranged(SLEEP_PATH), ListSerializer(SleepDto.serializer()))) {
                is StrideResult.Err -> return r
                is StrideResult.Ok -> sleeps += r.value.orEmpty()
            }
            when (val r = getJson(url("$HRV_PATH/$s/$e"), HrvEnvelope.serializer())) {
                is StrideResult.Err -> return r
                is StrideResult.Ok -> hrv += r.value?.hrvSummaries.orEmpty()
            }
            when (val r = getJson(url("$STRESS_PATH/$s/$e"), ListSerializer(StressDayDto.serializer()))) {
                is StrideResult.Err -> return r
                is StrideResult.Ok -> stress += r.value.orEmpty()
            }
            val weightUrl = url("$WEIGHT_PATH/$s/$e").newBuilder().addQueryParameter("includeAll", "false").build()
            when (val r = getJson(weightUrl, WeightEnvelope.serializer())) {
                is StrideResult.Err -> return r
                is StrideResult.Ok -> weights += r.value?.dailyWeightSummaries.orEmpty()
            }
        }
        return StrideResult.Ok(mergeWellness(dailies, sleeps, hrv, stress, weights))
    }

    // Plumbing

    private fun url(path: String): HttpUrl = config.baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()

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
                        StrideResult.Err(GarminError.Parse(e::class.simpleName ?: "decode error"))
                    }
                }
            }
        }
    }

    private fun authorized(url: HttpUrl, accept: String): Request? =
        Request.Builder().url(url).header("Accept", accept).get().build()

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
            sleep(maxOf(retryAfter, backoff))
            backoff = min(backoff * 2, config.maxBackoffMs)
            attempt++
        }
    }

    private fun retryDelayMs(error: GarminError): Long? = when (error) {
        is GarminError.RateLimited -> (error.retryAfterSec ?: 0) * 1000
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
        const val ACTIVITIES_PATH = "activitylist-service/activities/search/activities"
        const val DETAIL_PATH = "activity-service/activity"
        const val FIT_PATH = "download-service/files/activity"
        const val DAILIES_PATH = "usersummary-service/v2/dailysummaries"
        const val SLEEP_PATH = "sleep-service/sleep/dailySleepSummariesByDate"
        const val HRV_PATH = "hrv-service/hrv/daily"
        const val STRESS_PATH = "usersummary-service/stats/stress/daily"
        const val WEIGHT_PATH = "weight-service/weight/range"
        private const val DEFAULT_TOKEN_TTL_SEC = 24L * 3600

        /** Returns the first `.fit` entry of a ZIP, or the bytes themselves if they already are a FIT file. */
        fun unzipFit(bytes: ByteArray): ByteArray? {
            if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") return bytes
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (!entry.isDirectory && entry.name.endsWith(".fit", ignoreCase = true)) return zip.readBytes()
                }
            }
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
