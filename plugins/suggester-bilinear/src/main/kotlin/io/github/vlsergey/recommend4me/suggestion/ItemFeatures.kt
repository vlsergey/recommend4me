package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.stream.IntStream

/**
 * THE FEATURES OF ITEMS AS THE PRODUCTS READ THEM, NEVER LAID OUT WHOLE: of the layout of a
 * [Design], every input block's vectors where the task holds them — a matrix a block, times the
 * block's scale — and the features that are one or nought — the constant, the marks of having a
 * block, the values of the other facets — as the list of every item's ones. Laid out whole, the
 * 75 621 works of fanfics.me were 27 018 numbers each: two billion, eighteen thousand of them the
 * values of the other facets, all but a few nought, and the vectors a second copy of the task's own.
 *
 * Every product of the items' features goes through here, in both layouts of the weights: A
 * (width × rank) of [Bilinear], Aᵀ (rank × width, models one under another) of [Ensemble].
 */
internal class ItemFeatures(
    /** How many items: the rows of every block. */
    val rows: Int,
    /** How many features an item has in the layout. */
    val width: Int,
    val blocks: List<Dense>,
    /** Every item's features that are one, each once. */
    val ones: Array<IntArray>,
) {
    /** A block's vectors, a row an item, nought of an item without it; its first coordinate at [column] of the layout, every one times [scale]. */
    class Dense(val vectors: Matrix, val column: Int, val scale: Float) {
        val dim: Int get() = vectors.cols
    }

    /** U = X A: [a] width × [rank], into [out] rows × rank. */
    fun times(a: Floats, rank: Int, out: Floats) {
        IntStream.range(0, rows).parallel().forEach { i ->
            val at = i * rank
            for (q in 0 until rank) {
                var s = 0f
                for (c in ones[i]) s += a[c * rank + q]
                out[at + q] = s
            }
        }
        blocks.forEach { b ->
            Blas.gemm(false, false, rows, rank, b.dim, b.vectors.held, 0, b.dim, a, b.column * rank, rank, out, 0, rank, beta = 1f, alpha = b.scale)
        }
    }

    /** dA = Xᵀ dU: [du] rows × [rank], into [out] width × rank — a feature no item has, nought. */
    fun transposedTimes(du: Floats, rank: Int, out: Floats) {
        out.clear(0, width * rank)
        // An item after another: the items share their ones
        for (i in 0 until rows) {
            val at = i * rank
            for (c in ones[i]) for (q in 0 until rank) out[c * rank + q] = out[c * rank + q] + du[at + q]
        }
        blocks.forEach { b ->
            Blas.gemm(true, false, b.dim, rank, rows, b.vectors.held, 0, b.dim, du, 0, rank, out, b.column * rank, rank, alpha = b.scale)
        }
    }

    /** Uᵀ = Aᵀ Xᵀ of the first [k] rows of [aT] (k × width): into [uT], k × rows. */
    fun transposedCoordinates(aT: Floats, k: Int, uT: Floats) {
        onesCoordinates(aT, k, uT)
        blocks.forEach { b ->
            Blas.gemm(false, true, k, rows, b.dim, aT, b.column, width, b.vectors.held, 0, b.dim, uT, 0, rows, beta = 1f, alpha = b.scale)
        }
    }

    /** dAᵀ = dUᵀ X of the first [k] rows of [duT] (k × rows): into [gA], k × width. */
    fun transposedGradient(duT: Floats, k: Int, gA: Floats) {
        onesGradient(duT, k, gA)
        blocks.forEach { b ->
            Blas.gemm(false, false, k, b.dim, rows, duT, 0, rows, b.vectors.held, 0, b.dim, gA, b.column, width, alpha = b.scale)
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
}
