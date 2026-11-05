package org.stride.core.data.sync

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers
import org.stride.core.common.StrideResult
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.database.DatabaseHolder
import org.stride.core.database.entity.SyncStateEntity
import org.stride.core.database.localDate
import org.stride.core.database.toEntity
import org.stride.core.database.toModel
import org.stride.core.datastore.UserPreferences
import org.stride.core.garmin.GarminClient
import org.stride.core.garmin.GarminError
import org.stride.core.garmin.dateWindows
import org.stride.core.garmin.fit.FitParser
import org.stride.core.model.Activity

/** What the worker should do next. */
sealed interface SyncOutcome {
    data class Success(val newActivities: Int) : SyncOutcome

    /** Transient (network, 429, 5xx): WorkManager retries with backoff. */
    data class Retry(val reason: SyncFailure) : SyncOutcome

    /** Permanent until the user acts (missing or rejected key). */
    data class Stop(val reason: SyncFailure) : SyncOutcome
}

/**
 * Pulls Garmin data straight onto the phone (MVP-008):
 * 1. **Incremental:** the last few days, so new runs appear first.
 * 2. **Backfill:** newest-first ≤ 28-day windows back to the configured window
 *    (default 180 days), resuming from a cursor stored in Room after each window.
 * 3. **FIT:** laps and streams for runs that don't have them yet.
 * Every write is an idempotent upsert, so re-running a window never duplicates.
 */
@Singleton
class GarminSyncer @Inject constructor(
    private val client: GarminClient,
    private val holder: DatabaseHolder,
    private val prefs: UserPreferences,
    private val clock: StrideClock,
    private val status: SyncStatus,
    private val recomputer: MetricsRecomputer,
    @Dispatcher(StrideDispatchers.IO) private val io: CoroutineDispatcher
) {
    private class Abort(val error: GarminError) : Exception()

    @Suppress("CyclomaticComplexMethod") // one linear sync sequence; splitting hides the order of steps
    suspend fun sync(): SyncOutcome = withContext(io) {
        val settings = prefs.current()
        if (settings.demoMode || holder.isDemo) return@withContext SyncOutcome.Success(0)
        val db = holder.db
        val today = clock.today()
        val target = today.minusDays(settings.syncWindowDays - 1L)
        val stored = db.syncStateDao().get()
        val state = when {
            stored == null -> SyncStateEntity(
                backfillTargetDate = target.toString(),
                backfillCursorDate = null,
                backfillComplete = false,
                lastSuccessEpochSec = null
            )

            // The user widened the sync window: resume the backfill further back.
            stored.backfillTargetDate?.let(LocalDate::parse)?.isAfter(target) == true ->
                stored.copy(backfillTargetDate = target.toString(), backfillComplete = false)

            else -> stored
        }
        db.syncStateDao().upsert(state)

        val newIds = mutableSetOf<Long>()
        var earliestChanged: LocalDate? = null
        fun noteChanged(activities: List<Activity>) {
            activities.forEach { a ->
                val d = LocalDate.parse(a.localDate(clock.zone()))
                if (earliestChanged == null || d < earliestChanged) earliestChanged = d
            }
        }

        try {
            // 1. Incremental: the recent week (cheap; catches edits and late uploads).
            status.update(SyncProgress.Running("Checking for new runs", 0, 1))
            val recentStart = maxOf(target, today.minusDays(INCREMENTAL_DAYS - 1L))
            fetchWindow(recentStart, today, newIds, ::noteChanged)

            // 2. Backfill, resumable.
            if (!state.backfillComplete) {
                val cursor = state.backfillCursorDate?.let(LocalDate::parse) ?: recentStart
                val windows = dateWindows(target, cursor.minusDays(1), WINDOW_DAYS)
                windows.forEachIndexed { i, w ->
                    status.update(SyncProgress.Running("Importing history", i, windows.size))
                    fetchWindow(w.start, w.end, newIds, ::noteChanged)
                    db.syncStateDao().upsert(state.copy(backfillCursorDate = w.start.toString(), backfillComplete = w.start <= target))
                }
                db.syncStateDao().upsert(
                    (db.syncStateDao().get() ?: state).copy(backfillCursorDate = target.toString(), backfillComplete = true)
                )
            }

            // 3. FIT files for runs without streams (newest first).
            val missing = db.activityDao().idsMissingStreams(RUN_SPORTS)
            missing.forEachIndexed { i, id ->
                status.update(SyncProgress.Running("Downloading run details", i, missing.size))
                when (val fit = client.fitFile(id)) {
                    is StrideResult.Err -> if (fit.error.isFatal()) throw Abort(fit.error)

                    is StrideResult.Ok -> {
                        val parsed = FitParser.parse(fit.value, id)
                        if (parsed is StrideResult.Ok) {
                            db.withTransaction {
                                db.lapDao().replace(id, parsed.value.laps.map { it.toEntity() })
                                db.streamDao().upsert(parsed.value.streams.toEntity())
                                db.activityDao().markHasStreams(id)
                            }
                            newIds += id
                            db.activityDao().get(id)?.let { e -> noteChanged(listOf(e.toModel())) }
                        }
                        // An unparseable FIT is skipped: summary-level metrics still work.
                    }
                }
            }
        } catch (abort: Abort) {
            return@withContext failure(abort.error)
        }

        status.update(SyncProgress.Running("Computing your fitness", 0, 1))
        recomputer.recompute(changedActivityIds = newIds, fromDate = earliestChanged?.toString())
        val now = clock.now().epochSecond
        db.syncStateDao().upsert((db.syncStateDao().get() ?: state).copy(lastSuccessEpochSec = now))
        prefs.setLastSync(now)
        prefs.setGarminKeyRejected(false)
        status.update(SyncProgress.Succeeded(now, newIds.size))
        SyncOutcome.Success(newIds.size)
    }

    private suspend fun fetchWindow(start: LocalDate, end: LocalDate, newIds: MutableSet<Long>, noteChanged: (List<Activity>) -> Unit) {
        val db = holder.db
        val activities = when (val r = client.activities(start, end)) {
            is StrideResult.Err -> throw Abort(r.error)
            is StrideResult.Ok -> r.value
        }
        val wellness = when (val r = client.wellness(start, end)) {
            is StrideResult.Err -> throw Abort(r.error)
            is StrideResult.Ok -> r.value
        }
        val existing = db.activityDao().existingIds(activities.map { it.id }).toSet()
        // Keep hasStreams for activities we already decoded.
        val withStreamFlag = activities.map { a ->
            if (a.id in existing) a.copy(hasStreams = db.activityDao().get(a.id)?.hasStreams ?: false) else a
        }
        db.withTransaction {
            db.activityDao().upsert(withStreamFlag.map { it.toEntity(clock.zone()) })
            db.wellnessDao().upsert(wellness.map { it.toEntity() })
        }
        newIds += activities.map { it.id }.filterNot { it in existing }
        noteChanged(activities)
    }

    private suspend fun failure(error: GarminError): SyncOutcome {
        val (outcome, reason) = when (error) {
            GarminError.KeyMissing -> SyncOutcome.Stop(SyncFailure.KEY_MISSING) to SyncFailure.KEY_MISSING

            GarminError.KeyInvalid -> {
                prefs.setGarminKeyRejected(true)
                SyncOutcome.Stop(SyncFailure.KEY_INVALID) to SyncFailure.KEY_INVALID
            }

            is GarminError.Network -> SyncOutcome.Retry(SyncFailure.OFFLINE) to SyncFailure.OFFLINE

            is GarminError.RateLimited -> SyncOutcome.Retry(SyncFailure.RATE_LIMITED) to SyncFailure.RATE_LIMITED

            is GarminError.Server -> SyncOutcome.Retry(SyncFailure.SERVER) to SyncFailure.SERVER

            is GarminError.Http, is GarminError.Parse -> SyncOutcome.Retry(SyncFailure.UNEXPECTED) to SyncFailure.UNEXPECTED
        }
        status.update(SyncProgress.Failed(reason))
        return outcome
    }

    private fun GarminError.isFatal() = this == GarminError.KeyInvalid || this == GarminError.KeyMissing || this is GarminError.RateLimited

    companion object {
        const val WINDOW_DAYS = 28
        const val INCREMENTAL_DAYS = 7
        val RUN_SPORTS = listOf("RUN", "TRAIL_RUN", "TREADMILL_RUN")
    }
}
