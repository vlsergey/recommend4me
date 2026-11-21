package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ROWS OF A BYTE A NUMBER, each row with a scale of its own: a number is its byte (-127..127) times its row's scale,
 * the largest of the row 127 bytes (owner, 2026-11-20: the vectors of the texts the search of the duplicates holds, in
 * half the memory of half precision). A cosine of two unit rows so moves by a few thousandths. The bytes are off the
 * heap, belonging to the garbage collector (`Arena.ofAuto`); the scales on it.
 */
class ByteMatrix
private constructor(
    override val rows: Int,
    override val cols: Int,
    private val seg: MemorySegment,
    private val scales: FloatArray,
    private val first: Int,
) : Rows {
    constructor(
        rows: Int,
        cols: Int,
    ) : this(rows, cols, Arena.ofAuto().allocate((rows.toLong() * cols).coerceAtLeast(1)), FloatArray(rows), 0)

    override fun rows(fromRow: Int, count: Int) =
        ByteMatrix(count, cols, slice(fromRow, count), scales, first + fromRow)

    /** Row [i] made of [v]'s [cols] numbers: of its largest, 127. */
    fun put(i: Int, v: FloatArray) {
        val scale = scaleOf(v, cols)
        scales[first + i] = scale
        val at = i.toLong() * cols
        for (q in 0 until cols) seg.set(ValueLayout.JAVA_BYTE, at + q, byteOf(v[q], scale))
    }

    /**
     * Row [i] as [packed] holds it ([pack]): its scale and its bytes as they are; false, the row left, of a row of
     * another number of numbers.
     */
    fun putPacked(i: Int, packed: ByteArray, offset: Int = 0, length: Int = packed.size - offset): Boolean {
        if (length - SCALE_BYTES != cols) return false
        scales[first + i] =
            Float.fromBits(
                (0 until SCALE_BYTES).fold(0) { bits, k ->
                    bits or ((packed[offset + k].toInt() and BYTE) shl (k * BYTE_BITS))
                }
            )
        MemorySegment.copy(packed, offset + SCALE_BYTES, seg, ValueLayout.JAVA_BYTE, i.toLong() * cols, cols)
        return true
    }

    override fun take(i: Int, into: FloatArray) {
        val scale = scales[first + i]
        val at = i.toLong() * cols
        for (q in 0 until cols) into[q] = seg.get(ValueLayout.JAVA_BYTE, at + q) * scale
    }

    override fun floats(fromRow: Int, count: Int, into: Floats) {
        IntStream.range(0, count).parallel().forEach { r ->
            val scale = scales[first + fromRow + r]
            val at = (fromRow + r).toLong() * cols
            val to = r * cols
            for (q in 0 until cols) into[to + q] = seg.get(ValueLayout.JAVA_BYTE, at + q) * scale
        }
    }

    /** The bytes of the rows [fromRow] to [fromRow] + [count]: what is sent to the card. */
    internal fun slice(fromRow: Int, count: Int): MemorySegment =
        seg.asSlice(fromRow.toLong() * cols, count.toLong() * cols)

    /** The scales of the rows [fromRow] to [fromRow] + [count]. */
    fun scales(fromRow: Int, count: Int): FloatArray = scales.copyOfRange(first + fromRow, first + fromRow + count)

    companion object {
        /** The byte of the largest number of a row. */
        private const val LARGEST = 127f

        /** The bytes of a row's scale before its numbers' as a row is packed: a float, little-endian. */
        private const val SCALE_BYTES = Float.SIZE_BYTES

        private const val BYTE = 0xff
        private const val BYTE_BITS = Byte.SIZE_BITS

        /**
         * The row [v] as a row of these is kept: its scale (a float, little-endian) and its numbers a byte each — what
         * a store keeps a row by and [putPacked] takes back as it is.
         */
        fun pack(v: FloatArray): ByteArray {
            val scale = scaleOf(v, v.size)
            val out = ByteArray(SCALE_BYTES + v.size)
            val bits = scale.toRawBits()
            for (k in 0 until SCALE_BYTES) out[k] = (bits ushr (k * BYTE_BITS)).toByte()
            for (q in v.indices) out[SCALE_BYTES + q] = byteOf(v[q], scale)
            return out
        }

        /** The numbers a packed row of [bytes] holds. */
        fun packedCols(bytes: Int): Int = bytes - SCALE_BYTES

        private fun scaleOf(v: FloatArray, cols: Int): Float {
            var largest = 0f
            for (q in 0 until cols) largest = maxOf(largest, abs(v[q]))
            return if (largest > 0f) largest / LARGEST else 0f
        }

        private fun byteOf(x: Float, scale: Float): Byte = if (scale == 0f) 0 else (x / scale).roundToInt().toByte()
    }
}
