package org.stride.core.garmin.fit

import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.LapMesg
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.ByteArrayInputStream
import org.stride.core.common.StrideResult
import org.stride.core.model.ActivityStreams
import org.stride.core.model.Lap

/** A decoded FIT activity: laps and per-sample streams, all SI units (MVP-007). */
data class FitActivity(
    val startEpochSec: Long?,
    val laps: List<Lap>,
    val streams: ActivityStreams,
    /** `session.sport` from the file, e.g. "RUNNING", when present. */
    val sport: String?
)

/** Why a FIT file could not be decoded. Never thrown; returned as a typed result. */
sealed interface FitError {
    data object Empty : FitError

    data object NotFit : FitError

    /** Bad CRC, truncated file or a decoder failure. */
    data class Corrupt(val reason: String) : FitError

    /** The file decoded but contains no record messages. */
    data object NoRecords : FitError
}

/**
 * Decodes FIT bytes with the official Garmin FIT Java SDK.
 *
 * Time budget: a 2-hour 1 Hz run (7,200 records) decodes in well under 2 s on
 * the JVM test machine (asserted by `FitParserTest.twoHourRunWithinBudget`).
 */
object FitParser {
    private const val FIT_EPOCH_OFFSET_SEC = 631_065_600L

    /** Running cadence in FIT is "strides" (one foot) per minute; Stride reports steps per minute. */
    private const val STEPS_PER_STRIDE = 2.0

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // one pass over optional FIT fields; early returns are typed errors
    fun parse(bytes: ByteArray, activityId: Long): StrideResult<FitActivity, FitError> {
        if (bytes.isEmpty()) return StrideResult.Err(FitError.Empty)
        if (!Decode().isFileFit(ByteArrayInputStream(bytes))) return StrideResult.Err(FitError.NotFit)
        if (!Decode().checkFileIntegrity(ByteArrayInputStream(bytes))) {
            return StrideResult.Err(FitError.Corrupt("integrity check failed (truncated or bad CRC)"))
        }
        // A fresh decoder: isFileFit/checkFileIntegrity leave a Decode mid-stream.
        val decode = Decode()
        val records = ArrayList<RecordMesg>(4096)
        val laps = mutableListOf<LapMesg>()
        var session: SessionMesg? = null
        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener { mesg: RecordMesg -> records += mesg }
        broadcaster.addListener { mesg: LapMesg -> laps += mesg }
        broadcaster.addListener { mesg: SessionMesg -> if (session == null) session = mesg }
        try {
            decode.read(ByteArrayInputStream(bytes), broadcaster, broadcaster)
        } catch (e: FitRuntimeException) {
            return StrideResult.Err(FitError.Corrupt(e.message?.take(120) ?: "decode failed"))
        }
        if (records.isEmpty()) return StrideResult.Err(FitError.NoRecords)

        val isRun = session?.sport?.name?.contains("RUNNING") == true || session?.sport?.name == "RUNNING"
        val t0 = records.first().timestamp?.timestamp ?: 0L
        val n = records.size
        val time = DoubleArray(n)
        val hr = DoubleArray(n) { Double.NaN }
        val speed = DoubleArray(n) { Double.NaN }
        val dist = DoubleArray(n) { Double.NaN }
        val alt = DoubleArray(n) { Double.NaN }
        val cad = DoubleArray(n) { Double.NaN }
        var hasHr = false
        var hasSpeed = false
        var hasDist = false
        var hasAlt = false
        var hasCad = false
        records.forEachIndexed { i, r ->
            time[i] = ((r.timestamp?.timestamp ?: t0) - t0).toDouble()
            r.heartRate?.let {
                hr[i] = it.toDouble()
                hasHr = true
            }
            (r.enhancedSpeed ?: r.speed)?.let {
                speed[i] = it.toDouble()
                hasSpeed = true
            }
            r.distance?.let {
                dist[i] = it.toDouble()
                hasDist = true
            }
            (r.enhancedAltitude ?: r.altitude)?.let {
                alt[i] = it.toDouble()
                hasAlt = true
            }
            r.cadence?.let {
                val whole = it.toDouble() + (r.fractionalCadence?.toDouble() ?: 0.0)
                cad[i] = if (isRun) whole * STEPS_PER_STRIDE else whole
                hasCad = true
            }
        }
        val streams = ActivityStreams(
            activityId = activityId,
            timeSec = time,
            heartRate = hr.takeIf { hasHr },
            speedMps = speed.takeIf { hasSpeed },
            distanceM = dist.takeIf { hasDist },
            altitudeM = alt.takeIf { hasAlt },
            cadenceSpm = cad.takeIf { hasCad }
        )
        val lapModels = laps.mapIndexed { index, lap ->
            val lapStart = lap.startTime?.timestamp ?: t0
            Lap(
                activityId = activityId,
                index = index,
                startOffsetSec = (lapStart - t0).toDouble(),
                durationSec = (lap.totalTimerTime ?: lap.totalElapsedTime ?: 0f).toDouble(),
                distanceM = (lap.totalDistance ?: 0f).toDouble(),
                avgHr = lap.avgHeartRate?.toDouble(),
                maxHr = lap.maxHeartRate?.toDouble(),
                avgSpeedMps = (lap.enhancedAvgSpeed ?: lap.avgSpeed)?.toDouble(),
                elevationGainM = lap.totalAscent?.toDouble(),
                avgCadenceSpm = (lap.avgRunningCadence ?: lap.avgCadence)?.toDouble()?.let { if (isRun) it * STEPS_PER_STRIDE else it }
            )
        }
        return StrideResult.Ok(
            FitActivity(
                startEpochSec = if (t0 > 0) t0 + FIT_EPOCH_OFFSET_SEC else null,
                laps = lapModels,
                streams = streams,
                sport = session?.sport?.name
            )
        )
    }
}
