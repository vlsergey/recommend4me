package io.github.vlsergey.recommend4me.matrix

/**
 * ROWS OF NUMBERS, A ROW A THING, as a product reads them, however they are kept: every row of its [cols] numbers whole
 * ([HalfMatrix], two bytes a number), only the numbers not nought of each ([SparseRows]: the trigrams of a short text,
 * a few of hundreds), or a byte a number and a scale a row ([ByteMatrix]: the vectors of texts and the trigrams of long
 * ones, half the memory of half precision). A product on the processor reads a run of rows as single precision
 * ([floats]); the card is sent them made whole halves.
 */
sealed interface Rows {
    val rows: Int
    val cols: Int

    /** The rows [fromRow] to [fromRow] + [count] as rows of their own, over the same memory: nothing copied. */
    fun rows(fromRow: Int, count: Int): Rows

    /** Row [i] as single precision, into [into]. */
    fun take(i: Int, into: FloatArray)

    /** The rows [fromRow] to [fromRow] + [count] as single precision, into [into] from its start, a row after a row. */
    fun floats(fromRow: Int, count: Int, into: Floats)

    /** The product of the row [i] and the row [j] of [other], of as many numbers. */
    fun dot(i: Int, other: Rows, j: Int): Float {
        val x = FloatArray(cols).also { take(i, it) }
        val y = FloatArray(cols).also { other.take(j, it) }
        var sum = 0f
        for (q in 0 until cols) sum += x[q] * y[q]
        return sum
    }
}
