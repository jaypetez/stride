package org.stride.core.database

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs stream channels as little-endian float32 (4 bytes/sample); NaN marks a gap. */
object StreamCodec {
    fun encode(values: DoubleArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it.toFloat()) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): DoubleArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return DoubleArray(buffer.remaining()) { buffer.get(it).toDouble() }
    }
}
