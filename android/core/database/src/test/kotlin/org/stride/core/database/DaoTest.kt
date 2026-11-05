package org.stride.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stride.core.database.entity.DailyLoadEntity
import org.stride.core.database.entity.SyncStateEntity
import org.stride.core.model.Activity
import org.stride.core.model.ActivityStreams
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.Lap
import org.stride.core.model.SportType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DaoTest {
    private lateinit var db: StrideDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StrideDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun activity(id: Long, start: Long, name: String = "Run $id") = Activity(
        id = id,
        name = name,
        sport = SportType.RUN,
        startEpochSec = start,
        timeZone = "Europe/Berlin",
        durationSec = 3000.0,
        movingTimeSec = 2950.0,
        distanceM = 10_000.0,
        elevationGainM = 50.0,
        avgHr = 150.0,
        maxHr = 170.0,
        avgSpeedMps = 3.33,
        maxSpeedMps = 4.1,
        avgCadenceSpm = 172.0,
        calories = 600.0,
        source = DataSource.GARMIN
    )

    @Test
    fun activityUpsertIsIdempotentAndOrderedNewestFirst() = runTest {
        val dao = db.activityDao()
        dao.upsert(listOf(activity(1, 1_000), activity(2, 2_000)).map { it.toEntity() })
        dao.upsert(listOf(activity(1, 1_000, name = "Renamed")).map { it.toEntity() })
        val all = dao.observeAll().first()
        assertEquals(listOf(2L, 1L), all.map { it.id })
        assertEquals("Renamed", all.last().name)
        assertEquals(2, dao.count())
        assertEquals(activity(1, 1_000, "Renamed"), dao.get(1)!!.toModel())
    }

    @Test
    fun localDateUsesActivityTimeZone() {
        // 23:30 UTC on 31 Oct is 00:30 on 1 Nov in Berlin (CET, UTC+1).
        val late = activity(1, java.time.Instant.parse("2026-10-31T23:30:00Z").epochSecond)
        assertEquals("2026-11-01", late.toEntity().localDate)
        assertEquals("2026-10-31", late.copy(timeZone = null).toEntity().localDate)
    }

    @Test
    fun dateRangeFlowEmitsUpdates() = runTest {
        val dao = db.activityDao()
        dao.observeBetween("2026-11-01", "2026-11-07").test {
            assertEquals(emptyList(), awaitItem())
            dao.upsert(listOf(activity(5, java.time.Instant.parse("2026-11-03T07:00:00Z").epochSecond).toEntity()))
            assertEquals(listOf(5L), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun lapsAndStreamsRoundTripAndCascadeOnActivityKey() = runTest {
        db.activityDao().upsert(listOf(activity(7, 1).toEntity()))
        val laps = (0 until 3).map { Lap(7, it, it * 300.0, 300.0, 1000.0, 150.0, 160.0, 3.3, 5.0, 172.0) }
        db.lapDao().replace(7, laps.map { it.toEntity() })
        db.lapDao().replace(7, laps.take(2).map { it.toEntity() })
        assertEquals(laps.take(2), db.lapDao().get(7).map { it.toModel() })

        val streams = ActivityStreams(
            activityId = 7,
            timeSec = doubleArrayOf(0.0, 1.0, 2.0),
            heartRate = doubleArrayOf(140.0, Double.NaN, 142.0),
            speedMps = doubleArrayOf(3.0, 3.25, 3.5)
        )
        db.streamDao().upsert(streams.toEntity())
        val back = db.streamDao().get(7)!!.toModel()
        assertContentEquals(streams.timeSec, back.timeSec)
        assertTrue(back.heartRate!![1].isNaN())
        assertEquals(142.0, back.heartRate!![2])
        assertNull(back.altitudeM)
    }

    @Test
    fun wellnessUpsertByDate() = runTest {
        val dao = db.wellnessDao()
        dao.upsert(listOf(DailyWellness(date = "2026-11-01", restingHr = 50).toEntity()))
        dao.upsert(listOf(DailyWellness(date = "2026-11-01", restingHr = 48, sleepSec = 25_000).toEntity()))
        val all = dao.all()
        assertEquals(1, all.size)
        assertEquals(48, all.single().restingHr)
        assertEquals(DailyWellness(date = "2026-11-01", restingHr = 48, sleepSec = 25_000), dao.observeLatest().first()!!.toModel())
    }

    @Test
    fun dailyLoadsAreReplacedFromAChangedDate() = runTest {
        val dao = db.metricsDao()
        fun day(d: String, load: Double) = DailyLoadEntity(d, load, 1.0, 1.0, 0.0, null, null, null)
        dao.upsertDailyLoads(listOf(day("2026-11-01", 10.0), day("2026-11-02", 20.0), day("2026-11-03", 30.0)))
        dao.replaceDailyLoadsFrom("2026-11-02", listOf(day("2026-11-02", 25.0)))
        assertEquals(listOf(10.0, 25.0), dao.allDailyLoads().map { it.load })
    }

    @Test
    fun syncStateSingleRow() = runTest {
        val dao = db.syncStateDao()
        assertNull(dao.get())
        dao.upsert(
            SyncStateEntity(
                backfillTargetDate = "2026-05-01",
                backfillCursorDate = null,
                backfillComplete = false,
                lastSuccessEpochSec = null
            )
        )
        dao.upsert(
            SyncStateEntity(
                backfillTargetDate = "2026-05-01",
                backfillCursorDate = "2026-10-01",
                backfillComplete = false,
                lastSuccessEpochSec = 5
            )
        )
        assertEquals("2026-10-01", dao.get()!!.backfillCursorDate)
    }
}
