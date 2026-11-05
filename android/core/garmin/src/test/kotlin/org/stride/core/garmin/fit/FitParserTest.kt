package org.stride.core.garmin.fit

import com.garmin.fit.CRC
import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgBroadcaster
import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.stride.core.common.StrideResult

class FitParserTest {
    private fun fixture(name: String): ByteArray = java.io.File("src/test/resources/fit/$name").readBytes()

    @Test
    fun decodesCommittedSampleRun() {
        val result = FitParser.parse(fixture("easy-run-30min.fit"), activityId = 7)
        assertIs<StrideResult.Ok<FitActivity>>(result)
        val activity = result.value
        assertEquals(1800, activity.streams.size)
        assertEquals("RUNNING", activity.sport)
        assertEquals(SyntheticFit.START.epochSecond, activity.startEpochSec)
        // ~3.1 m/s for 30 min ≈ 5.6 km → 5 full 1-km laps + a partial one.
        assertEquals(6, activity.laps.size)
        assertEquals(0.0, activity.streams.timeSec.first())
        assertEquals(1799.0, activity.streams.timeSec.last())
        assertEquals(140.0, activity.streams.heartRate!!.first())
        // Running cadence is converted from strides/min (86) to steps/min (172).
        assertEquals(172.0, activity.streams.cadenceSpm!!.first())
        assertEquals(172.0, activity.laps.first().avgCadenceSpm)
        assertEquals(3.1, activity.streams.speedMps!!.first(), 1e-3)
        assertTrue(activity.laps.dropLast(1).all { it.distanceM >= 1000.0 && it.distanceM < 1004.0 })
        assertEquals(activity.streams.distanceM!!.last(), activity.laps.sumOf { it.distanceM }, 1.0)
    }

    @Test
    fun missingHeartRateLeavesChannelNull() {
        val result = FitParser.parse(SyntheticFit.run(120, withHeartRate = false), activityId = 1)
        assertIs<StrideResult.Ok<FitActivity>>(result)
        assertNull(result.value.streams.heartRate)
        assertEquals(120, result.value.streams.size)
    }

    @Test
    fun corruptAndPartialFilesAreTypedErrors() {
        val good = SyntheticFit.run(300)
        assertEquals(StrideResult.Err(FitError.Empty), FitParser.parse(ByteArray(0), 1))
        assertEquals(StrideResult.Err(FitError.NotFit), FitParser.parse("not a fit file at all".toByteArray(), 1))
        val truncated = FitParser.parse(good.copyOf(good.size / 2), 1)
        assertIs<StrideResult.Err<FitError>>(truncated)
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertIs<StrideResult.Err<FitError>>(FitParser.parse(flipped, 1))
    }

    /** AUD-028: no session message → cadence falls back to the Garmin summary's sport instead of being halved. */
    @Test
    fun missingSessionFallsBackToSummarySportForCadence() {
        val bytes = SyntheticFit.records(listOf(0, 1, 2), withSession = false)
        val asRun = FitParser.parse(bytes, activityId = 1, fallbackIsRun = true)
        assertIs<StrideResult.Ok<FitActivity>>(asRun)
        assertNull(asRun.value.sport)
        assertEquals(172.0, asRun.value.streams.cadenceSpm!!.first())
        val notRun = FitParser.parse(bytes, activityId = 1)
        assertEquals(86.0, (notRun as StrideResult.Ok).value.streams.cadenceSpm!!.first())
        // A session in the file still wins over the fallback.
        val withSession = FitParser.parse(SyntheticFit.records(listOf(0, 1), withSession = true), 1, fallbackIsRun = false)
        assertEquals(172.0, (withSession as StrideResult.Ok).value.streams.cadenceSpm!!.first())
    }

    /** AUD-028: records without a timestamp are dropped (not placed at time 0) and the rest are sorted. */
    @Test
    fun timestamplessRecordsAreDroppedAndOutOfOrderRecordsSorted() {
        // HR is 100 + file index: indices 0..4 at offsets 10, -, 5, 20, -.
        val bytes = SyntheticFit.records(listOf(10, null, 5, 20, null), withSession = true)
        val result = FitParser.parse(bytes, activityId = 1)
        assertIs<StrideResult.Ok<FitActivity>>(result)
        val streams = result.value.streams
        assertEquals(listOf(0.0, 5.0, 15.0), streams.timeSec.toList())
        assertEquals(listOf(102.0, 100.0, 103.0), streams.heartRate!!.toList())
        assertEquals(SyntheticFit.START.epochSecond + 5, result.value.startEpochSec)
    }

    @Test
    fun onlyTimestamplessRecordsMeansNoRecords() {
        val bytes = SyntheticFit.records(listOf(null, null), withSession = false)
        assertEquals(StrideResult.Err(FitError.NoRecords), FitParser.parse(bytes, 1))
    }

    /**
     * AUD-028: the never-throws contract holds for hostile input that passes the CRC check:
     * random bytes are flipped and the file CRC is recomputed so the decoder really runs.
     */
    @Test
    fun corruptedFilesWithValidCrcNeverThrow() {
        val good = SyntheticFit.run(60)
        val random = Random(42)
        val outcomes = mutableMapOf<String, Int>()
        repeat(300) {
            val bad = good.copyOf()
            repeat(1 + random.nextInt(8)) {
                val i = HEADER_SIZE + random.nextInt(bad.size - HEADER_SIZE - 2)
                bad[i] = random.nextInt(256).toByte()
            }
            // Must return a typed result, whatever the bytes are: a throw here fails the test.
            val result = FitParser.parse(withValidCrc(bad), 1)
            val key = when (result) {
                is StrideResult.Ok -> "ok"
                is StrideResult.Err -> result.error::class.simpleName!!
            }
            outcomes.merge(key, 1, Int::plus)
        }
        assertEquals(300, outcomes.values.sum())
        // The CRC fix-up means the decoder itself rejects most mutations, not the integrity check.
        assertTrue((outcomes["Corrupt"] ?: 0) > 0, "decoder failures are typed as Corrupt: $outcomes")
    }

    /**
     * AUD-028: the SDK throws plain RuntimeExceptions, not only FitRuntimeException, on input that passes
     * the CRC check. These single-byte corruptions of the synthetic run were found by fuzzing; each first
     * proves the raw SDK really throws that non-FIT exception (so this test fails on a parser that only
     * caught FitRuntimeException), then that FitParser turns it into FitError.Corrupt.
     */
    @Test
    fun nonFitRuntimeExceptionsFromTheSdkAreCorrupt() {
        // Byte 19 is the first definition message's field count (5 -> 22): the SDK indexes past its field table.
        val badFieldCount = corrupt(index = 19, value = 22)
        assertFailsWith<ArrayIndexOutOfBoundsException> { rawSdkDecode(badFieldCount) }
        assertEquals(
            StrideResult.Err(FitError.Corrupt("ArrayIndexOutOfBoundsException")),
            FitParser.parse(badFieldCount, 1)
        )

        // A record header turned into a developer-data message with no developer data index: the SDK NPEs.
        val badDeveloperData = corrupt(index = 1217, value = 207)
        assertFailsWith<NullPointerException> { rawSdkDecode(badDeveloperData) }
        assertEquals(
            StrideResult.Err(FitError.Corrupt("NullPointerException")),
            FitParser.parse(badDeveloperData, 1)
        )
    }

    /** The synthetic 60 s run with one byte replaced and the file CRC recomputed, so the CRC still matches. */
    private fun corrupt(index: Int, value: Int): ByteArray {
        val bytes = SyntheticFit.run(60)
        bytes[index] = value.toByte()
        return withValidCrc(bytes)
    }

    private fun withValidCrc(bytes: ByteArray): ByteArray {
        var crc = 0
        for (i in 0 until bytes.size - 2) crc = CRC.get16(crc, bytes[i])
        bytes[bytes.size - 2] = (crc and 0xFF).toByte()
        bytes[bytes.size - 1] = (crc shr 8 and 0xFF).toByte()
        return bytes
    }

    /** Decodes with the bare SDK, letting any non-FitRuntimeException escape. */
    private fun rawSdkDecode(bytes: ByteArray) {
        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)
        try {
            decode.read(ByteArrayInputStream(bytes), broadcaster, broadcaster)
        } catch (e: FitRuntimeException) {
            throw AssertionError("expected a non-FIT RuntimeException, got $e", e)
        }
    }

    /** Documented budget (FitParser KDoc): a 2-hour 1 Hz run decodes in < 2 s on the JVM. */
    @Test
    fun twoHourRunWithinBudget() {
        val bytes = SyntheticFit.run(7200)
        FitParser.parse(bytes, 1) // warm-up
        val ms = measureTimeMillis {
            val result = FitParser.parse(bytes, 1)
            assertIs<StrideResult.Ok<FitActivity>>(result)
            assertEquals(7200, result.value.streams.size)
        }
        assertTrue(ms < 2_000, "decoding took $ms ms")
    }

    /** Regenerates the committed sample with `-Dstride.writeFitFixtures=true`. */
    @Test
    fun committedSampleMatchesGenerator() {
        val generated = SyntheticFit.run(1800)
        if (System.getProperty("stride.writeFitFixtures") == "true") {
            java.io.File("src/test/resources/fit/easy-run-30min.fit").apply { parentFile.mkdirs() }.writeBytes(generated)
        }
        val decodedCommitted = (FitParser.parse(fixture("easy-run-30min.fit"), 1) as StrideResult.Ok).value
        val decodedGenerated = (FitParser.parse(generated, 1) as StrideResult.Ok).value
        assertEquals(decodedGenerated.streams, decodedCommitted.streams)
    }

    private companion object {
        const val HEADER_SIZE = 14
    }
}
