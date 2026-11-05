package org.stride.core.data.demo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.stride.core.model.Activity
import org.stride.core.model.ActivityStreams
import org.stride.core.model.DailyWellness
import org.stride.core.model.DataSource
import org.stride.core.model.Lap
import org.stride.core.model.SportType

/** One synthetic run or ride with its laps and streams. */
data class DemoActivity(val activity: Activity, val laps: List<Lap>, val streams: ActivityStreams?)

data class DemoDataset(val activities: List<DemoActivity>, val wellness: List<DailyWellness>)

/**
 * A deterministic, synthetic training history (MVP-020): ~26 weeks of a
 * recreational runner building toward a half marathon, with 3:1 load/recovery
 * weeks, easy runs, intervals, tempo and long runs, plus daily wellness.
 * Same [seed] + same [today] → byte-identical data. No real person's data.
 */
object DemoDataGenerator {
    const val DEFAULT_SEED = 2026L
    const val WEEKS = 26
    private const val SAMPLE_SEC = 5
    private const val ZONE = "Europe/London"

    private enum class Kind(val title: String) {
        EASY("Easy run"),
        RECOVERY("Recovery jog"),
        INTERVALS("6 × 800 m intervals"),
        TEMPO("Tempo run"),
        LONG("Long run"),
        RIDE("Easy spin")
    }

    @Suppress("LoopWithTooManyJumpStatements")
    fun generate(today: LocalDate, seed: Long = DEFAULT_SEED): DemoDataset {
        val random = Random(seed)
        val start = today.minusWeeks(WEEKS.toLong()).with(DayOfWeek.MONDAY)
        val activities = mutableListOf<DemoActivity>()
        var id = 9_000_000_000L
        // WEEKS full weeks of history plus the current (partial) week up to today.
        for (week in 0..WEEKS) {
            val recoveryWeek = week % 4 == 3
            val progress = minOf(1.0, week / (WEEKS - 1.0))
            // Threshold pace improves from ~4:40/km to ~4:15/km over the block.
            val thresholdMps = 1000.0 / (280.0 - 25.0 * progress)
            val volume = if (recoveryWeek) 0.7 else 1.0
            val plan = listOf(
                DayOfWeek.TUESDAY to Kind.EASY,
                DayOfWeek.THURSDAY to if (week % 2 == 0) Kind.INTERVALS else Kind.TEMPO,
                DayOfWeek.FRIDAY to Kind.RECOVERY,
                DayOfWeek.SUNDAY to Kind.LONG
            ) + if (week % 2 == 1) listOf(DayOfWeek.SATURDAY to Kind.RIDE) else emptyList()
            for ((day, kind) in plan) {
                val date = start.plusWeeks(week.toLong()).with(day)
                if (date.isAfter(today)) continue
                if (random.nextDouble() < 0.06) continue // the odd missed session
                val hour = if (day == DayOfWeek.SUNDAY) 8 else 7
                val startSec = date.atTime(hour, random.nextInt(0, 45)).toEpochSecond(ZoneOffset.UTC)
                activities += session(++id, kind, startSec, thresholdMps, volume, progress, random)
            }
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt()
        val wellness = (0..days).map { offset ->
            val date = start.plusDays(offset.toLong())
            val fatigue = sin(offset / 7.0 * PI / 2)
            DailyWellness(
                date = date.toString(),
                restingHr = (51 - 3 * (offset / (WEEKS * 7.0)) + 2 * fatigue + random.nextDouble(-1.0, 1.0)).roundToInt(),
                hrvLastNightMs = (58 + 6 * (offset / (WEEKS * 7.0)) - 4 * fatigue + random.nextDouble(-5.0, 5.0)).round1(),
                hrvWeeklyAvgMs = (58 + 6 * (offset / (WEEKS * 7.0))).round1(),
                hrvStatus = if (fatigue > 0.8) "UNBALANCED" else "BALANCED",
                sleepSec = (7.3 * 3600 + random.nextDouble(-2700.0, 2700.0)).roundToInt(),
                sleepScore = (78 + random.nextDouble(-12.0, 12.0)).roundToInt(),
                stressAvg = (28 + random.nextDouble(-8.0, 10.0)).roundToInt(),
                bodyBatteryHigh = (82 + random.nextDouble(-12.0, 15.0)).roundToInt().coerceAtMost(100),
                bodyBatteryLow = (18 + random.nextDouble(-8.0, 10.0)).roundToInt().coerceAtLeast(5),
                steps = (9000 + random.nextDouble(-3000.0, 5000.0)).roundToInt(),
                weightKg = (71.5 - 1.5 * (offset / (WEEKS * 7.0)) + random.nextDouble(-0.3, 0.3)).round1(),
                source = DataSource.DEMO
            )
        }.filter { !LocalDate.parse(it.date).isAfter(today) }
        return DemoDataset(activities.sortedBy { it.activity.startEpochSec }, wellness)
    }

    /** Builds 5-second streams whose summary numbers are derived from the samples. */
    @Suppress("LongMethod")
    private fun session(
        id: Long,
        kind: Kind,
        startSec: Long,
        threshold: Double,
        volume: Double,
        progress: Double,
        random: Random
    ): DemoActivity {
        if (kind == Kind.RIDE) {
            val dur = (45 + random.nextInt(0, 20)) * 60.0
            val activity = Activity(
                id = id, name = kind.title, sport = SportType.CYCLE, startEpochSec = startSec, timeZone = ZONE,
                durationSec = dur, movingTimeSec = dur, distanceM = dur * 7.2, elevationGainM = 120.0, avgHr = 122.0,
                maxHr = 141.0, avgSpeedMps = 7.2, maxSpeedMps = 11.0, avgCadenceSpm = null, calories = dur / 6,
                source = DataSource.DEMO
            )
            return DemoActivity(activity, emptyList(), null)
        }
        // Segments of (seconds, speed as a fraction of threshold, target HR fraction of max 190).
        val easy = 0.78
        val segments: List<Triple<Int, Double, Double>> = when (kind) {
            Kind.EASY -> listOf(Triple((40 + 15 * progress * volume).toInt() * 60, easy, 0.72))

            Kind.RECOVERY -> listOf(Triple((28 * volume).toInt() * 60, 0.72, 0.66))

            Kind.LONG -> listOf(Triple(((70 + 45 * progress) * volume).toInt() * 60, 0.76, 0.74))

            Kind.TEMPO -> listOf(
                Triple(12 * 60, easy, 0.72),
                Triple((15 + 10 * progress).toInt() * 60, 0.98, 0.88),
                Triple(10 * 60, easy, 0.74)
            )

            Kind.INTERVALS -> buildList {
                add(Triple(15 * 60, easy, 0.72))
                repeat(6) {
                    add(Triple(170, 1.1, 0.93))
                    add(Triple(90, 0.65, 0.78))
                }
                add(Triple(10 * 60, easy, 0.72))
            }

            Kind.RIDE -> emptyList()
        }
        val time = mutableListOf<Double>()
        val hr = mutableListOf<Double>()
        val speed = mutableListOf<Double>()
        val dist = mutableListOf<Double>()
        val alt = mutableListOf<Double>()
        val cad = mutableListOf<Double>()
        var t = 0
        var d = 0.0
        var currentHr = 95.0
        for ((seconds, speedFrac, hrFrac) in segments) {
            val steps = seconds / SAMPLE_SEC
            repeat(steps) {
                val hill = sin(d / 900.0) * 8.0
                val v = threshold * speedFrac * (1 + random.nextDouble(-0.03, 0.03)) * (1 - hill / 400)
                // HR rises toward the segment target with slow cardiac drift over the run.
                val target = 190 * hrFrac + t / 600.0
                currentHr += (target - currentHr) * 0.12 + random.nextDouble(-1.0, 1.0)
                time += t.toDouble()
                hr += currentHr.round1()
                speed += v.round3()
                dist += d.round1()
                alt += (40 + hill * 5 + 6 * sin(d / 2500.0)).round1()
                cad += (164 + 14 * speedFrac + random.nextDouble(-2.0, 2.0)).round1()
                d += v * SAMPLE_SEC
                t += SAMPLE_SEC
            }
        }
        val laps = mutableListOf<Lap>()
        var lapStartIdx = 0
        var lapStartDist = 0.0
        for (i in dist.indices) {
            val end = i == dist.lastIndex
            if (dist[i] - lapStartDist >= 1000.0 || end) {
                val range = lapStartIdx..i
                val lapDur = (time[i] - time[lapStartIdx]).coerceAtLeast(SAMPLE_SEC.toDouble())
                laps += Lap(
                    activityId = id,
                    index = laps.size,
                    startOffsetSec = time[lapStartIdx],
                    durationSec = lapDur,
                    distanceM = (dist[i] - lapStartDist).round1(),
                    avgHr = range.map { hr[it] }.average().round1(),
                    maxHr = range.maxOf { hr[it] },
                    avgSpeedMps = ((dist[i] - lapStartDist) / lapDur).round3(),
                    elevationGainM = range.zipWithNext().sumOf { (a, b) -> (alt[b] - alt[a]).coerceAtLeast(0.0) }.round1(),
                    avgCadenceSpm = range.map { cad[it] }.average().round1()
                )
                lapStartIdx = i
                lapStartDist = dist[i]
            }
        }
        val duration = time.last() + SAMPLE_SEC
        val activity = Activity(
            id = id,
            name = kind.title,
            sport = SportType.RUN,
            startEpochSec = startSec,
            timeZone = ZONE,
            durationSec = duration,
            movingTimeSec = duration,
            distanceM = d.round1(),
            elevationGainM = alt.zipWithNext().sumOf { (a, b) -> (b - a).coerceAtLeast(0.0) }.round1(),
            avgHr = hr.average().round1(),
            maxHr = hr.max(),
            avgSpeedMps = (d / duration).round3(),
            maxSpeedMps = speed.max(),
            avgCadenceSpm = cad.average().round1(),
            calories = (d / 1000 * 68).round1(),
            source = DataSource.DEMO,
            hasStreams = true
        )
        val streams = ActivityStreams(
            activityId = id,
            timeSec = time.toDoubleArray(),
            heartRate = hr.toDoubleArray(),
            speedMps = speed.toDoubleArray(),
            distanceM = dist.toDoubleArray(),
            altitudeM = alt.toDoubleArray(),
            cadenceSpm = cad.toDoubleArray()
        )
        return DemoActivity(activity, laps, streams)
    }

    private fun Double.round1() = (this * 10).roundToInt() / 10.0

    private fun Double.round3() = (this * 1000).roundToInt() / 1000.0
}
