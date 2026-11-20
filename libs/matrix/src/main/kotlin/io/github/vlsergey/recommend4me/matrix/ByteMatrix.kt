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
        val largest = (0 until cols).maxOfOrNull { abs(v[it]) } ?: 0f
        val scale = if (largest > 0f) largest / LARGEST else 0f
        scales[first + i] = scale
        val at = i.toLong() * cols
        for (q in 0 until cols) seg.set(
            ValueLayout.JAVA_BYTE,
            at + q,
            if (scale == 0f) 0 else (v[q] / scale).roundToInt().toByte(),
        )
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

    private companion object {
        /** The byte of the largest number of a row. */
        const val LARGEST = 127f
    }
}
