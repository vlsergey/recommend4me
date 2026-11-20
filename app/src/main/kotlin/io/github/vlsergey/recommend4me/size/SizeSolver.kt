package io.github.vlsergey.recommend4me.size

import kotlin.math.sqrt

/**
 * THE WEIGHED RIDGE OF THE WORDS TO A CHARACTER SOLVED — the normal equations (Aᵀ W A + Λ) x = Aᵀ W y of the examples'
 * rows — by conjugate gradients preconditioned by the matrix's diagonal, on the processor ([SizeOnProcessor]) or wholly
 * on the card ([SizeOnCard]). Begun at a solution given, else at the examples' weighted mean; done when the residual,
 * each feature's by the root of its diagonal, is the tolerance of what the examples' spread about their mean asks of
 * it: their level, the mean's, would drown the authors' steps (2026-11-20: the steps of the authors solved to nought
 * so, their variance with them).
 */
internal interface SizeSolver : AutoCloseable {
    /**
     * The solution of the examples weighed by [weights], each feature held by its [lambda], begun at [from], done at
     * the [tolerance] or after [most] steps.
     */
    fun solve(weights: DoubleArray, lambda: DoubleArray, from: DoubleArray?, tolerance: Double, most: Int): Solved

    override fun close() {}

    /** The solution [x] and the steps it took. */
    class Solved(val x: DoubleArray, val steps: Int)
}

/** The ridge solved on the processor, the examples in parts side by side. */
internal class SizeOnProcessor(private val rows: SizeRows) : SizeSolver {
    override fun solve(
        weights: DoubleArray,
        lambda: DoubleArray,
        from: DoubleArray?,
        tolerance: Double,
        most: Int,
    ): SizeSolver.Solved {
        val n = rows.features
        val diagonal = rows.diagonal(weights).also { d -> for (f in 0 until n) d[f] += lambda[f] }
        val b = rows.right(weights)
        val mean = b[0] / diagonal[0].coerceAtLeast(Double.MIN_VALUE)
        val spread = rows.scattered { i -> weights[i] * (rows.y[i] - mean) }
        val enough = tolerance * sqrt(scaled(spread, diagonal))
        val x = from?.copyOf() ?: DoubleArray(n).also { it[0] = mean }
        val r = times(weights, x, lambda).let { ax -> DoubleArray(n) { b[it] - ax[it] } }
        val z = DoubleArray(n) { if (diagonal[it] > 0) r[it] / diagonal[it] else 0.0 }
        val p = z.copyOf()
        var rz = dot(r, z)
        var taken = 0
        while (taken < most && sqrt(rz) > enough) {
            taken++
            val ap = times(weights, p, lambda)
            val alpha = rz / dot(p, ap)
            for (f in 0 until n) {
                x[f] += alpha * p[f]
                r[f] -= alpha * ap[f]
                z[f] = if (diagonal[f] > 0) r[f] / diagonal[f] else 0.0
            }
            val next = dot(r, z)
            val beta = next / rz
            for (f in 0 until n) p[f] = z[f] + beta * p[f]
            rz = next
        }
        return SizeSolver.Solved(x, taken)
    }

    /** aᵀ D⁻¹ a: every feature's square by its [diagonal]. */
    private fun scaled(a: DoubleArray, diagonal: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) if (diagonal[i] > 0) sum += a[i] * a[i] / diagonal[i]
        return sum
    }

    /** (Aᵀ W A + Λ) [x] of the examples' [weights]. */
    private fun times(weights: DoubleArray, x: DoubleArray, lambda: DoubleArray): DoubleArray =
        rows
            .scattered { i -> weights[i] * rows.dot(i, x) }
            .also { out -> for (f in out.indices) out[f] += lambda[f] * x[f] }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
