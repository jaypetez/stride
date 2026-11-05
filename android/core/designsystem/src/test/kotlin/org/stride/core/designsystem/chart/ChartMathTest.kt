package org.stride.core.designsystem.chart

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test
    fun scaleCoversValuesAndIncludesWithNiceTicks() {
        val s = ChartMath.scale(listOf(-12.3, 47.9, Double.NaN), include = listOf(0.0))
        assertTrue(s.min <= -12.3 && s.max >= 47.9)
        assertEquals(listOf(-20.0, 0.0, 20.0, 40.0, 60.0), s.ticks)
    }

    @Test
    fun scaleOfConstantOrEmptySeriesIsNonDegenerate() {
        assertTrue(ChartMath.scale(listOf(5.0, 5.0)).span > 0)
        assertEquals(AxisScale(0.0, 1.0, listOf(0.0, 1.0)), ChartMath.scale(emptyList()))
    }

    @Test
    fun scaleIncludesBandEdges() {
        val s = ChartMath.scale(listOf(1.0, 1.1), include = listOf(0.8, 1.3, 1.5))
        assertTrue(s.min <= 0.8 && s.max >= 1.5)
    }

    @Test
    fun niceSteps() {
        assertEquals(1.0, ChartMath.niceStep(0.9))
        assertEquals(2.5, ChartMath.niceStep(2.3))
        assertEquals(50.0, ChartMath.niceStep(41.0))
        assertEquals(0.2, ChartMath.niceStep(0.17), 1e-12)
    }

    @Test
    fun downsampleAveragesBucketsAndKeepsGaps() {
        val values = doubleArrayOf(1.0, 3.0, Double.NaN, Double.NaN, 5.0, Double.NaN, 7.0)
        assertEquals(2, ChartMath.downsampleStride(values.size, 4))
        val out = ChartMath.downsample(values, 4)
        assertEquals(4, out.size)
        assertEquals(2.0, out[0])
        assertTrue(out[1].isNaN())
        assertEquals(5.0, out[2])
        assertEquals(7.0, out[3])
    }

    @Test
    fun downsampleIsIdentityWhenSmall() {
        assertEquals(listOf(1.0, 2.0), ChartMath.downsample(doubleArrayOf(1.0, 2.0), 500))
        val big = DoubleArray(10_000) { it.toDouble() }
        assertTrue(ChartMath.downsample(big, 300).size <= 300)
    }

    @Test
    fun defaultLabels() {
        assertEquals("40", ChartMath.defaultLabel(40.0))
        assertEquals("1.5", ChartMath.defaultLabel(1.5))
        assertEquals("0.25", ChartMath.defaultLabel(0.25))
    }
}
