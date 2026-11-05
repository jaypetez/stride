package org.stride.core.garmin.fit

import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import java.time.Instant
import kotlin.math.sin

/**
 * Builds deterministic FIT activity files with the FIT SDK encoder, so tests
 * exercise the real decoder without committing anyone's personal data.
 */
internal object SyntheticFit {
    val START: Instant = Instant.parse("2026-11-01T07:00:00Z")

    /**
     * A steady run at ~3.1 m/s for [durationSec] seconds at 1 Hz with HR, speed,
     * distance, altitude and cadence, and one lap per kilometre.
     */
    fun run(durationSec: Int, withHeartRate: Boolean = true): ByteArray {
        val file = kotlin.io.path.createTempFile("synthetic", ".fit").toFile()
        try {
            val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
            val t0 = DateTime(START)
            encoder.write(
                FileIdMesg().apply {
                    type = File.ACTIVITY
                    manufacturer = Manufacturer.DEVELOPMENT
                    product = 1
                    serialNumber = 1L
                    timeCreated = t0
                }
            )
            var distance = 0.0
            var lapStartSec = 0
            var lapStartDistance = 0.0
            var lapIndex = 0
            val lapHr = mutableListOf<Int>()
            fun writeLap(endSec: Int) {
                encoder.write(
                    LapMesg().apply {
                        messageIndex = lapIndex
                        startTime = DateTime(t0.timestamp + lapStartSec)
                        timestamp = DateTime(t0.timestamp + endSec)
                        totalElapsedTime = (endSec - lapStartSec).toFloat()
                        totalTimerTime = (endSec - lapStartSec).toFloat()
                        totalDistance = (distance - lapStartDistance).toFloat()
                        if (lapHr.isNotEmpty()) {
                            avgHeartRate = lapHr.average().toInt().toShort()
                            maxHeartRate = lapHr.max().toShort()
                        }
                        enhancedAvgSpeed = ((distance - lapStartDistance) / (endSec - lapStartSec).coerceAtLeast(1)).toFloat()
                        totalAscent = 5
                        avgRunningCadence = 86
                        event = Event.LAP
                        eventType = EventType.STOP
                    }
                )
                lapIndex++
                lapStartSec = endSec
                lapStartDistance = distance
                lapHr.clear()
            }
            for (s in 0 until durationSec) {
                val speed = 3.1 + 0.2 * sin(s / 60.0)
                if (s > 0) distance += speed
                val hr = (140 + 10 * sin(s / 300.0) + s / 600.0).toInt()
                lapHr += hr
                encoder.write(
                    RecordMesg().apply {
                        timestamp = DateTime(t0.timestamp + s)
                        if (withHeartRate) heartRate = hr.toShort()
                        enhancedSpeed = speed.toFloat()
                        this.distance = distance.toFloat()
                        enhancedAltitude = (50 + 10 * sin(s / 400.0)).toFloat()
                        cadence = 86
                    }
                )
                if (distance - lapStartDistance >= 1000.0) writeLap(s)
            }
            if (durationSec - 1 > lapStartSec) writeLap(durationSec - 1)
            encoder.write(
                SessionMesg().apply {
                    startTime = t0
                    timestamp = DateTime(t0.timestamp + durationSec)
                    sport = Sport.RUNNING
                    totalElapsedTime = durationSec.toFloat()
                    totalTimerTime = durationSec.toFloat()
                    totalDistance = distance.toFloat()
                    numLaps = lapIndex
                }
            )
            encoder.close()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /**
     * Bare records in file order, one per entry of [offsetsSec] (seconds after [START];
     * null writes a record without a timestamp), each with HR 100 + its index and
     * cadence 86. No laps; a running session message only when [withSession].
     */
    fun records(offsetsSec: List<Int?>, withSession: Boolean): ByteArray {
        val file = kotlin.io.path.createTempFile("synthetic", ".fit").toFile()
        try {
            val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
            val t0 = DateTime(START)
            encoder.write(
                FileIdMesg().apply {
                    type = File.ACTIVITY
                    manufacturer = Manufacturer.DEVELOPMENT
                    product = 1
                    serialNumber = 1L
                    timeCreated = t0
                }
            )
            offsetsSec.forEachIndexed { i, offset ->
                encoder.write(
                    RecordMesg().apply {
                        if (offset != null) timestamp = DateTime(t0.timestamp + offset)
                        heartRate = (100 + i).toShort()
                        cadence = 86
                    }
                )
            }
            if (withSession) {
                encoder.write(
                    SessionMesg().apply {
                        startTime = t0
                        timestamp = DateTime(t0.timestamp + offsetsSec.filterNotNull().max())
                        sport = Sport.RUNNING
                    }
                )
            }
            encoder.close()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
