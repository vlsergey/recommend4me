package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.stream.IntStream

/**
 * ROW-MAJOR ROWS OF HALF-PRECISION NUMBERS (IEEE fp16) in one off-heap block: the vectors of the
 * texts and the pictures as the database keeps them, two bytes a number — half the memory of
 * [Matrix], half of what goes to the card. A product reads them as single precision: a run of rows
 * at a time made floats ([floats]), on the card by a kernel of its own.
 *
 * The memory belongs to the garbage collector (`Arena.ofAuto`), as [Floats]'.
 */
class HalfMatrix(val rows: Int, val cols: Int) {
    internal val seg: MemorySegment = Arena.ofAuto().allocate(HALF, rows.toLong().times(cols).coerceAtLeast(1))

    /** Where row [i] begins, in numbers. */
    fun row(i: Int): Long = i.toLong() * cols

    operator fun get(i: Int, q: Int): Float = java.lang.Float.float16ToFloat(seg.getAtIndex(HALF, row(i) + q))

    /** Row [i] made of [v]'s [cols] numbers, rounded to half precision. */
    fun put(i: Int, v: FloatArray) {
        val at = row(i)
        for (q in 0 until cols) seg.setAtIndex(HALF, at + q, java.lang.Float.floatToFloat16(v[q]))
    }

    /** Row [i] made of [bytes]: [cols] half-precision numbers, little-endian, as the database keeps them — copied as they are. */
    fun putHalves(i: Int, bytes: ByteArray) {
        require(bytes.size == cols * 2) { "${bytes.size} bytes of a row of $cols" }
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, row(i) * 2, bytes.size.toLong())
    }

    /** Row [i] as single precision, into [into]. */
    fun take(i: Int, into: FloatArray) {
        val at = row(i)
        for (q in 0 until cols) into[q] = java.lang.Float.float16ToFloat(seg.getAtIndex(HALF, at + q))
    }

    /** [count] rows of [from] from its row [fromRow] on, written from row [atRow] on. */
    fun copyRows(from: HalfMatrix, fromRow: Int, atRow: Int, count: Int) {
        require(from.cols == cols) { "rows of ${from.cols} into rows of $cols" }
        MemorySegment.copy(from.seg, from.row(fromRow) * 2, seg, row(atRow) * 2, count.toLong() * cols * 2)
    }

    /** The rows [fromRow] to [fromRow] + [count] as single precision, into [into] from its start: a row on a core of its own. */
    fun floats(fromRow: Int, count: Int, into: Floats) {
        IntStream.range(0, count).parallel().forEach { r ->
            val at = row(fromRow + r)
            val to = r * cols
            for (q in 0 until cols) into[to + q] = java.lang.Float.float16ToFloat(seg.getAtIndex(HALF, at + q))
        }
    }

    /** The bytes of the rows [fromRow] to [fromRow] + [count]: what is sent to the card. */
    internal fun slice(fromRow: Int, count: Int): MemorySegment = seg.asSlice(row(fromRow) * 2, count.toLong() * cols * 2)

    /** The rows [indices] gathered into a new matrix. */
    fun gather(indices: IntArray): HalfMatrix {
        val out = HalfMatrix(indices.size, cols)
        indices.forEachIndexed { k, i -> out.copyRows(this, i, k, 1) }
        return out
    }

    companion object {
        private val HALF = ValueLayout.JAVA_SHORT

        init {
            // The database's bytes copied as they are: little-endian, as the memory of the machines this runs on
            check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) { "half-precision rows copied as bytes need a little-endian machine" }
        }

        /** The rows [indices] of [m] in half precision. */
        fun of(m: Matrix, indices: IntArray = IntArray(m.rows) { it }): HalfMatrix {
            val out = HalfMatrix(indices.size, m.cols)
            val row = FloatArray(m.cols)
            indices.forEachIndexed { k, i ->
                m.held.take(row, 0, m.row(i), m.cols)
                out.put(k, row)
            }
            return out
        }
    }
}
