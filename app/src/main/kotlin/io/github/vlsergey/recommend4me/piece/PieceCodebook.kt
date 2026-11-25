package io.github.vlsergey.recommend4me.piece

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * THE CODES OF THE PIECES' VECTORS: of every number of a projected piece ([PieceProjections]) its bucket of [LEVELS], 2
 * bits — 32 bytes a piece of 128 numbers — by the [cuts] of every number learnt with the projection, the bucket's
 * [means] read back, the vector made of unit length again. Nothing of a centroid: a code is read alone (owner,
 * 2026-11-25: of 85 retellings the MaxSim of the codes as good as of the numbers projected, 0.843 against 0.856).
 */
class PieceCodebook(val cuts: Array<FloatArray>, val means: Array<FloatArray>) {
    val width: Int
        get() = cuts.size

    /** The bytes of a code. */
    val bytes: Int
        get() = width / PER_BYTE

    /** The code of a piece's projected vector [v]. */
    fun code(v: FloatArray, into: ByteArray = ByteArray(bytes), at: Int = 0): ByteArray {
        for (j in 0 until width) {
            val b = bucket(v[j], cuts[j])
            val i = at + j / PER_BYTE
            into[i] = (into[i].toInt() or (b shl (j % PER_BYTE * BITS))).toByte()
        }
        return into
    }

    /** The codes of [vectors] one after another. */
    fun codes(vectors: List<FloatArray>): ByteArray {
        val out = ByteArray(vectors.size * bytes)
        vectors.forEachIndexed { k, v -> code(v, out, k * bytes) }
        return out
    }

    /** The vector of the code at [at] of [codes], of unit length. */
    fun decode(codes: ByteArray, at: Int = 0, into: FloatArray = FloatArray(width)): FloatArray {
        var n = 0.0
        for (j in 0 until width) {
            val b = (codes[at + j / PER_BYTE].toInt() shr (j % PER_BYTE * BITS)) and MASK
            into[j] = means[j][b]
            n += into[j].toDouble() * into[j]
        }
        val s = sqrt(n).toFloat()
        if (s > 0) for (j in 0 until width) into[j] /= s
        return into
    }

    /** The cuts and the means as bytes, every float little-endian: the cuts of every number, then its means. */
    fun pack(): ByteArray {
        val b = ByteBuffer.allocate(width * (LEVELS - 1 + LEVELS) * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        cuts.forEach { c -> c.forEach(b::putFloat) }
        means.forEach { m -> m.forEach(b::putFloat) }
        return b.array()
    }

    companion object {
        /** The bits of a number, the buckets of it, the numbers of a byte. */
        const val BITS = 2
        const val LEVELS = 1 shl BITS
        private const val MASK = LEVELS - 1
        private const val PER_BYTE = Byte.SIZE_BITS / BITS

        fun unpack(bytes: ByteArray, width: Int): PieceCodebook {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val cuts = Array(width) { FloatArray(LEVELS - 1) { b.getFloat() } }
            val means = Array(width) { FloatArray(LEVELS) { b.getFloat() } }
            return PieceCodebook(cuts, means)
        }

        /** The cuts of every number of the [vectors] at equal shares of them, and the mean of every bucket. */
        fun learnt(vectors: List<FloatArray>): PieceCodebook {
            val width = vectors.first().size
            val cuts = Array(width) { FloatArray(LEVELS - 1) }
            val means = Array(width) { FloatArray(LEVELS) }
            for (j in 0 until width) {
                val sorted = FloatArray(vectors.size) { vectors[it][j] }.also { it.sort() }
                for (l in 1 until LEVELS) cuts[j][l - 1] = sorted[sorted.size * l / LEVELS]
                val sum = DoubleArray(LEVELS)
                val n = IntArray(LEVELS)
                for (v in sorted) {
                    val k = bucket(v, cuts[j])
                    sum[k] += v.toDouble()
                    n[k]++
                }
                for (l in 0 until LEVELS) means[j][l] = if (n[l] > 0) (sum[l] / n[l]).toFloat() else 0f
            }
            return PieceCodebook(cuts, means)
        }

        private fun bucket(v: Float, cuts: FloatArray): Int {
            var b = 0
            while (b < cuts.size && v >= cuts[b]) b++
            return b
        }
    }
}
