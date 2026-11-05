package org.stride.core.garmin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Client tuning. The base URL is configurable so tests can point at MockWebServer. */
data class GarminConfig(
    val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    /** Every ranged call spans at most this many days (docs/garmin/personal-api.md). */
    val maxWindowDays: Int = 28,
    /** Activity search page size; Garmin returns 500 above ~45. */
    val pageSize: Int = 20,
    /** Retries for 408/429/5xx and network errors, after the first attempt. */
    val maxRetries: Int = 3,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    /** Re-exchange the key this long before the token's `exp`. */
    val tokenRefreshMarginSec: Long = 300
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://personal.connectapi.garmin.com"
    }
}
