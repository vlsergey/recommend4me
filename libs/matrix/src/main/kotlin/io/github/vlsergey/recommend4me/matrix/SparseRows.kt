package io.github.vlsergey.recommend4me.matrix

import java.util.stream.IntStream

/**
 * ROWS OF WHICH ONLY THE NUMBERS NOT NOUGHT ARE KEPT, in half precision: the counts of the trigrams of a short text — a
 * title, the names of its authors — are some twenty of hundreds of buckets, a tenth of the memory of the row whole
 * (owner, 2026-11-20: the search of the duplicates ran out of memory). Row [i]'s are the entries [at] (i) to [at] (i +
 * 1), each its column ([columns]) and its number ([values], half precision); a run of rows of others shares their
 * arrays from its [first] row on.
 */
class SparseRows
private constructor(
    override val rows: Int,
    override val cols: Int,
    private val at: IntArray,
    private val columns: ShortArray,
    private val values: ShortArray,
    private val first: Int,
) : Rows {
    init {
        require(cols <= MAX_COLS) { "$cols columns, more than a column's number holds" }
    }

    override fun rows(fromRow: Int, count: Int) = SparseRows(count, cols, at, columns, values, first + fromRow)

    /** The entries of every row, the numbers not nought kept. */
    val entryCount: Int
        get() = at[first + rows] - at[first]

    override fun take(i: Int, into: FloatArray) {
        into.fill(0f, 0, cols)
        for (k in at[first + i] until at[first + i + 1]) into[column(k)] = value(k)
    }

    override fun floats(fromRow: Int, count: Int, into: Floats) {
        IntStream.range(0, count).parallel().forEach { r ->
            val to = r * cols
            for (q in 0 until cols) into[to + q] = 0f
            for (k in at[first + fromRow + r] until at[first + fromRow + r + 1]) into[to + column(k)] = value(k)
        }
    }

    /** Of two sparse rows, the product of the columns both have; of another kind of rows, as any rows. */
    override fun dot(i: Int, other: Rows, j: Int): Float {
        if (other !is SparseRows) return super.dot(i, other, j)
        var sum = 0f
        var p = at[first + i]
        var q = other.at[other.first + j]
        val pEnd = at[first + i + 1]
        val qEnd = other.at[other.first + j + 1]
        while (p < pEnd && q < qEnd) {
            val c = column(p)
            val d = other.column(q)
            when {
                c == d -> sum += value(p++) * other.value(q++)
                c < d -> p++
                else -> q++
            }
        }
        return sum
    }

    /**
     * The entries of the rows [fromRow] to [fromRow] + [count] as the card is sent them: where every row's begin among
     * them, from nought ([Entries.at], one more than the rows), and every entry an int, its column in the lower two
     * bytes, its half in the higher ([Entries.packed]).
     */
    fun entries(fromRow: Int, count: Int): Entries {
        val from = at[first + fromRow]
        val to = at[first + fromRow + count]
        return Entries(
            IntArray(count + 1) { at[first + fromRow + it] - from },
            IntArray(to - from) { k ->
                column(from + k) or ((values[from + k].toInt() and COLUMN_MASK) shl Short.SIZE_BITS)
            },
        )
    }

    /** The entries of a run of rows ([entries]). */
    class Entries(val at: IntArray, val packed: IntArray)

    private fun column(k: Int) = columns[k].toInt() and COLUMN_MASK

    private fun value(k: Int) = java.lang.Float.float16ToFloat(values[k])

    companion object {
        /** The columns a row may have at most: a column's number is kept in two bytes. */
        const val MAX_COLS = 1 shl Short.SIZE_BITS
        private const val COLUMN_MASK = MAX_COLS - 1

        /**
         * The [count] rows of [cols] numbers [row] gives, a row on a core of its own: of each its numbers not nought, a
         * row of none of null.
         */
        fun of(count: Int, cols: Int, row: (Int) -> FloatArray?): SparseRows {
            val own = arrayOfNulls<ShortArray>(count)
            val ownValues = arrayOfNulls<ShortArray>(count)
            IntStream.range(0, count).parallel().forEach { i ->
                val v = row(i) ?: return@forEach
                val kept = (0 until cols).filter { v[it] != 0f }
                own[i] = ShortArray(kept.size) { kept[it].toShort() }
                ownValues[i] = ShortArray(kept.size) { java.lang.Float.floatToFloat16(v[kept[it]]) }
            }
            val at = IntArray(count + 1)
            for (i in 0 until count) at[i + 1] = at[i] + (own[i]?.size ?: 0)
            val columns = ShortArray(at[count])
            val values = ShortArray(at[count])
            for (i in 0 until count) {
                own[i]?.copyInto(columns, at[i])
                ownValues[i]?.copyInto(values, at[i])
            }
            return SparseRows(count, cols, at, columns, values, 0)
        }
    }
}
