package org.stride.core.garmin.fit

import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
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
}
