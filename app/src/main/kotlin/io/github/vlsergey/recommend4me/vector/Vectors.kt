package io.github.vlsergey.recommend4me.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.sqrt

/** How vectors are kept in the databases, and the keys and hashes of the texts they are of. */
object Vectors {

    /** float16 little-endian: a vector of unit length loses nothing that matters, and half the room. */
    fun half(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buffer.putShort(java.lang.Float.floatToFloat16(it)) }
        return buffer.array()
    }

    fun fromHalf(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { java.lang.Float.float16ToFloat(buffer.getShort()) }
    }

    /** float32 little-endian: what is not of unit length (set embeddings, strengths). */
    fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(v)
        return buffer.array()
    }

    fun unpack(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /** The key a short text's vector is kept under: the first 64 bits of its SHA-1. */
    fun keyOf(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (digest[i].toLong() and 0xFF)
        return h
    }

    /** The hash a vector of a long text is kept with: the text is encoded again when it changes. */
    fun hash(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > 1e-9) FloatArray(v.size) { (v[it] / n).toFloat() } else v
    }

    /** The mean direction of [vectors], unit again; null for none. */
    fun meanDirection(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val sum = FloatArray(vectors[0].size)
        vectors.forEach { v -> for (k in sum.indices) sum[k] += v[k] }
        var n = 0.0
        for (x in sum) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > 1e-9) FloatArray(sum.size) { (sum[it] / n).toFloat() } else null
    }
}
