package org.stride.core.garmin

import java.time.LocalDate
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness

/**
 * The Garmin Personal API from the phone (MVP-003, docs/garmin/personal-api.md).
 * Ranged calls are split into ≤ [GarminConfig.maxWindowDays]-day windows
 * internally; callers can pass any range.
 */
interface GarminClient {
    /**
     * Exchanges [apiKey] (or the stored key when null) for a 24h access token.
     * With an explicit key this validates it without touching the token cache,
     * which is what the Connect screen uses before saving.
     */
    suspend fun exchangeToken(apiKey: String? = null): GarminResult<AccessToken>

    /** Activities that started between [start] and [end] inclusive, newest first. */
    suspend fun activities(start: LocalDate, end: LocalDate): GarminResult<List<Activity>>

    /** One activity's summary enriched with its IANA time zone. */
    suspend fun activityDetail(activityId: Long): GarminResult<Activity>

    /** The original FIT file bytes (unzipped). */
    suspend fun fitFile(activityId: Long): GarminResult<ByteArray>

    /** Daily summaries, sleep, HRV, stress and weight merged per calendar date. */
    suspend fun wellness(start: LocalDate, end: LocalDate): GarminResult<List<DailyWellness>>

    /** Forgets the cached access token (used on disconnect / key change). */
    suspend fun clearToken()
}
