package io.github.vlsergey.recommend4me.fulltext

import java.nio.ByteBuffer

/** A window of a text: its characters [from] until [to] in the text, and its vector. */
class TextWindow(val from: Int, val to: Int, val vector: FloatArray)

/**
 * THE FULL-TEXT VECTORS OF THE WINDOWS, what a search by meaning reads of every window of every chapter and of every
 * text searched by meaning ([io.github.vlsergey.recommend4me.part.FullTextVectorRepository]), whole
 * ([FullTextNearest]). Made of a window's vector less the type's centre ([FullTextCentres]): of every number whether it
 * is above it — a bit a number, 128 bytes a window of bge-m3. A line's vector less the centre held against them, every
 * number with the sign of the window's (asymmetric: the line's numbers whole): measured on 85 retellings among 129 250
 * windows (owner, 2026-11-25), the window of the answer among the first 1 000 79 times as by the vectors themselves,
 * the chapter among the first 300 78 times against 79; of the signs not less the centre 71 times, of the signs of the
 * 256 leading directions 68, of a product quantization of 64 bytes 67.
 *
 * Kept of a text as one value: the bytes of a window's vector, how many windows, then of every window its characters
 * and its bits.
 */
object FullTextVectors {
    /** The full-text vectors of the [windows] of a text, less the [centre], as kept. */
    fun pack(windows: List<TextWindow>, centre: FloatArray): ByteArray {
        val bytes = (centre.size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        val out = ByteBuffer.allocate(HEAD + windows.size * (WINDOW_HEAD + bytes))
        out.putInt(bytes).putInt(windows.size)
        windows.forEach { w ->
            out.putInt(w.from).putInt(w.to)
            val signs = ByteArray(bytes)
            for (i in centre.indices) if (w.vector[i] > centre[i])
                signs[i / Byte.SIZE_BITS] = (signs[i / Byte.SIZE_BITS].toInt() or (1 shl (i % Byte.SIZE_BITS))).toByte()
            out.put(signs)
        }
        return out.array()
    }

    /** Every window of the text kept as [packed]: its characters, and where its signs begin in [packed]. */
    inline fun forEachWindow(packed: ByteArray, action: (from: Int, to: Int, at: Int) -> Unit) {
        val buffer = ByteBuffer.wrap(packed)
        val bytes = buffer.getInt(0)
        val count = buffer.getInt(Int.SIZE_BYTES)
        var at = HEAD
        repeat(count) {
            action(buffer.getInt(at), buffer.getInt(at + Int.SIZE_BYTES), at + WINDOW_HEAD)
            at += WINDOW_HEAD + bytes
        }
    }

    /**
     * A line's vector [q] as it is held against the signs: of every byte of the signs, of each of its 256 values, the
     * sum of the line's numbers less the [centre], each with the sign its bit says — a window's score the sum of a
     * byte's table of each of its bytes.
     */
    fun query(q: FloatArray, centre: FloatArray): Query {
        val bytes = (centre.size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        val tables =
            Array(bytes) { k ->
                FloatArray(VALUES) { b ->
                    var s = 0f
                    for (j in 0 until Byte.SIZE_BITS) {
                        val i = k * Byte.SIZE_BITS + j
                        if (i < centre.size) s += if ((b shr j) and 1 == 1) q[i] - centre[i] else centre[i] - q[i]
                    }
                    s
                }
            }
        return Query(tables)
    }

    /** A line as it is held against the signs ([query]). */
    class Query(private val tables: Array<FloatArray>) {
        /** The score of the window whose signs begin at [at] of [packed]: the higher, the nearer. */
        fun score(packed: ByteArray, at: Int): Float {
            var s = 0f
            for (k in tables.indices) s += tables[k][packed[at + k].toInt() and BYTE]
            return s
        }
    }

    /** The bytes before the windows: a window's bytes of signs, how many windows. */
    const val HEAD = 2 * Int.SIZE_BYTES

    /** The bytes of a window before its signs: its characters from and until. */
    const val WINDOW_HEAD = 2 * Int.SIZE_BYTES

    private const val VALUES = 256
    private const val BYTE = 0xff
}
