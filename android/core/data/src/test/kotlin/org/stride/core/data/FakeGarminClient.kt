package org.stride.core.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.stride.core.common.StrideResult
import org.stride.core.garmin.AccessToken
import org.stride.core.garmin.GarminClient
import org.stride.core.garmin.GarminError
import org.stride.core.garmin.GarminResult
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness

/** Serves a fixed set of activities and wellness by date; can fail on demand. */
class FakeGarminClient(
    private val activities: List<Activity>,
    private val wellness: List<DailyWellness> = emptyList()
) : GarminClient {
    val activityCalls = mutableListOf<Pair<LocalDate, LocalDate>>()
    var failActivitiesOnCall: Int? = null
    var failWith: GarminError = GarminError.Network("IOException")
    var fitRequests = mutableListOf<Long>()

    /** Thrown from `activities` (an unexpected failure, not a typed error). */
    var throwOnActivities: Throwable? = null

    /** When set, every `activities` call suspends until it completes. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Completes when the first `activities` call reaches [gate]. */
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** `activities` calls currently suspended on [gate]. */
    var inFlight = 0
    var maxInFlight = 0

    override suspend fun exchangeToken(apiKey: String?): GarminResult<AccessToken> = StrideResult.Ok(AccessToken("t", Long.MAX_VALUE))

    override suspend fun activities(start: LocalDate, end: LocalDate): GarminResult<List<Activity>> {
        activityCalls += start to end
        throwOnActivities?.let { throw it }
        gate?.let {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            entered.complete(Unit)
            try {
                it.await()
            } finally {
                inFlight--
            }
        }
        if (failActivitiesOnCall == activityCalls.size) return StrideResult.Err(failWith)
        return StrideResult.Ok(
            activities.filter {
                val d = java.time.Instant.ofEpochSecond(it.startEpochSec).atZone(ZoneOffset.UTC).toLocalDate()
                !d.isBefore(start) && !d.isAfter(end)
            }
        )
    }

    override suspend fun activityDetail(activityId: Long): GarminResult<Activity> = StrideResult.Err(GarminError.Http(404))

    override suspend fun fitFile(activityId: Long): GarminResult<ByteArray> {
        fitRequests += activityId
        return StrideResult.Err(GarminError.Http(404))
    }

    override suspend fun wellness(start: LocalDate, end: LocalDate): GarminResult<List<DailyWellness>> =
        StrideResult.Ok(wellness.filter { LocalDate.parse(it.date) in start..end })

    override suspend fun clearToken() = Unit
}
