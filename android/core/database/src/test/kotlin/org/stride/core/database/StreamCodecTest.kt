package org.stride.core.database

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StreamCodecTest {
    @Test
    fun roundTripsFloat32WithNaN() {
        val values = doubleArrayOf(0.0, 1.5, Double.NaN, 3.25, 7200.0)
        val back = StreamCodec.decode(StreamCodec.encode(values))
        assertEquals(values.size, back.size)
        assertTrue(back[2].isNaN())
        assertEquals(3.25, back[3])
        assertEquals(20, StreamCodec.encode(values).size)
    }
}
