package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.matrix.Simd
import java.util.stream.IntStream

/**
 * THE FEATURES OF ITEMS AS THE PRODUCTS READ THEM, NEVER LAID OUT WHOLE: of the layout of a
 * [Design], every input block's vectors as the task holds them — the rows of the items that have it
 * alone ([InputBlock]), times the block's scale — and the features that are one or nought — the
 * constant, the marks of having a block, the values of the other facets — as the list of every
 * item's ones. Laid out whole, the 75 621 works of fanfics.me were 27 018 numbers each: two billion,
 * eighteen thousand of them the values of the other facets, all but a few nought; a block of a row
 * of every item, its rows of the items without it nought — of the notes 75 584 of 75 630.
 *
 * A block's product is of its own rows, carried onto the items' rows (or columns) after, and the
 * items' taken from them for the products back: [scratch] holds a block's part between the two —
 * made once by whoever multiplies, as large as [scratchOf] says.
 *
 * Every product of the items' features goes through here, in both layouts of the weights: A
 * (width × rank) of [Bilinear], Aᵀ (rank × width, models one under another) of [Ensemble].
 */
internal class ItemFeatures(
    /** How many items. */
    val rows: Int,
    /** How many features an item has in the layout. */
    val width: Int,
    val blocks: List<Dense>,
    /** Every item's features that are one, each once. */
    val ones: Array<IntArray>,
) {
    /**
     * A block's vectors, a row of each item of [items] that has it; its mark of having it at [mark]
     * of the layout, its first coordinate after it, every one times [scale]. [card]: the same rows on
     * the card, as the task's input holds them there.
     */
    class Dense(val vectors: Matrix, val items: IntArray, val mark: Int, val scale: Float, val card: CardRows?) {
        val dim: Int get() = vectors.cols
        val count: Int get() = items.size
        val column: Int get() = mark + 1
    }

    /** The most rows of a block: what a block's part of a product of [k] columns or rows takes ([scratch]). */
    val widest: Int = blocks.maxOfOrNull { it.count } ?: 0

    /** Room for a block's part of a product of [k] coordinates. */
    fun scratchOf(k: Int): Floats = Floats(maxOf(1, k * widest))

    /** U = X A: [a] width × [rank], into [out] rows × rank. */
    fun times(a: Floats, rank: Int, out: Floats, scratch: Floats) {
        onesTimes(a, rank, out)
        blocksTimes(a, rank, out, scratch)
    }

    /** Of the ones alone, U = X A written into [out]: the blocks' part is added to it after ([blocksTimes]). */
    fun onesTimes(a: Floats, rank: Int, out: Floats) {
        IntStream.range(0, rows).parallel().forEach { i ->
            val at = i * rank
            for (q in 0 until rank) {
                var s = 0f
                for (c in ones[i]) s += a[c * rank + q]
                out[at + q] = s
            }
        }
    }

    /** Of the blocks' vectors, X A added to [out]: a block's rows multiplied, each added to its item's row. */
    fun blocksTimes(a: Floats, rank: Int, out: Floats, scratch: Floats) {
        blocks.forEach { b ->
            Blas.gemm(false, false, b.count, rank, b.dim, b.vectors.held, 0, b.dim, a, b.column * rank, rank, scratch, 0, rank, alpha = b.scale)
            // An item once in a block: no two of its rows on one
            IntStream.range(0, b.count).parallel().forEach { j -> Simd.axpy(1f, scratch, j * rank, out, b.items[j] * rank, rank) }
        }
    }

    /** dA = Xᵀ dU: [du] rows × [rank], into [out] width × rank — a feature no item has, nought. */
    fun transposedTimes(du: Floats, rank: Int, out: Floats, scratch: Floats) {
        out.clear(0, width * rank)
        // An item after another: the items share their ones
        for (i in 0 until rows) {
            val at = i * rank
            for (c in ones[i]) for (q in 0 until rank) out[c * rank + q] = out[c * rank + q] + du[at + q]
        }
        blocks.forEach { b ->
            IntStream.range(0, b.count).parallel().forEach { j -> scratch.copy(du, b.items[j] * rank, j * rank, rank) }
            Blas.gemm(true, false, b.dim, rank, b.count, b.vectors.held, 0, b.dim, scratch, 0, rank, out, b.column * rank, rank, alpha = b.scale)
        }
    }

    /** Uᵀ = Aᵀ Xᵀ of the first [k] rows of [aT] (k × width): into [uT], k × rows. */
    fun transposedCoordinates(aT: Floats, k: Int, uT: Floats, scratch: Floats) {
        onesCoordinates(aT, k, uT)
        blocks.forEach { b ->
            val count = b.count
            Blas.gemm(false, true, k, count, b.dim, aT, b.column, width, b.vectors.held, 0, b.dim, scratch, 0, count, alpha = b.scale)
            // A row of Uᵀ on a core of its own: the block's columns onto its items'
            IntStream.range(0, k).parallel().forEach { q ->
                val from = q * count
                val to = q * rows
                for (j in 0 until count) uT[to + b.items[j]] = uT[to + b.items[j]] + scratch[from + j]
            }
        }
    }

    /** dAᵀ = dUᵀ X of the first [k] rows of [duT] (k × rows): into [gA], k × width. */
    fun transposedGradient(duT: Floats, k: Int, gA: Floats, scratch: Floats) {
        onesGradient(duT, k, gA)
        blocks.forEach { b ->
            val count = b.count
            IntStream.range(0, k).parallel().forEach { q ->
                val from = q * rows
                val to = q * count
                for (j in 0 until count) scratch[to + j] = duT[from + b.items[j]]
            }
            Blas.gemm(false, false, k, b.dim, count, scratch, 0, count, b.vectors.held, 0, b.dim, gA, b.column, width, alpha = b.scale)
        }
    }

    /** Of the ones alone, Uᵀ = Aᵀ Xᵀ written into [uT]: the blocks' part is added to it after. A row of Aᵀ on a core of its own. */
    fun onesCoordinates(aT: Floats, k: Int, uT: Floats) {
        IntStream.range(0, k).parallel().forEach { q ->
            val aAt = q * width
            val uAt = q * rows
            for (i in 0 until rows) {
                var s = 0f
                for (c in ones[i]) s += aT[aAt + c]
                uT[uAt + i] = s
            }
        }
    }

    /**
     * Of the ones alone, dAᵀ = dUᵀ X written into [gA] — the blocks' columns nought, written by
     * their products after. A row of dAᵀ on a core of its own: none written by two.
     */
    fun onesGradient(duT: Floats, k: Int, gA: Floats) {
        IntStream.range(0, k).parallel().forEach { q ->
            val gAt = q * width
            val uAt = q * rows
            gA.clear(gAt, width)
            for (i in 0 until rows) {
                val d = duT[uAt + i]
                if (d == 0f) continue
                for (c in ones[i]) gA[gAt + c] = gA[gAt + c] + d
            }
        }
    }

    /** Every item's ones one after another, and where each item's begin: of [rows] + 1, the last their number. As a kernel reads them. */
    fun onesFlat(): Pair<IntArray, IntArray> {
        val at = IntArray(rows + 1)
        for (i in 0 until rows) at[i + 1] = at[i] + ones[i].size
        val flat = IntArray(at[rows])
        for (i in 0 until rows) ones[i].copyInto(flat, at[i])
        return at to flat
    }
}
