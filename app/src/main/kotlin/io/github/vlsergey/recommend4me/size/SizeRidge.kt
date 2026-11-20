package io.github.vlsergey.recommend4me.size

import kotlin.math.sqrt

/**
 * THE WEIGHED RIDGE OF THE WORDS TO A CHARACTER SOLVED: the normal equations (Aᵀ W A + Λ) x = Aᵀ W y of the examples'
 * [rows], of the weights and the penalties asked, by conjugate gradients preconditioned by the matrix's diagonal, till
 * the residual is [TOLERANCE] of what the spread asks — not a number of steps —, the product with the matrix of the
 * [gram], on the card or the processor. The folds of the cross-validation each begun of the solution of every example.
 */
internal class SizeRidge(private val rows: SizeRows, private val gram: SizeGram) {

    /**
     * How far from the truth the logarithm of the ratio is told of an example the model did not see — the mean square
     * of the folds' errors, its root —, of the examples' [weights] and the [lambda] fitted: every fold's model begun at
     * [whole], the solution of every example.
     */
    fun outOfFold(weights: DoubleArray, lambda: DoubleArray, whole: DoubleArray): Double {
        if (rows.count < FOLDS) return Double.NaN
        var sum = 0.0
        for (fold in 0 until FOLDS) {
            val x = solve(DoubleArray(rows.count) { if (foldOf(it) == fold) 0.0 else weights[it] }, lambda, whole)
            for (i in 0 until rows.count) if (foldOf(i) == fold) sum += (rows.y[i] - rows.dot(i, x)).let { it * it }
        }
        return sqrt(sum / rows.count)
    }

    /** The same of the mean alone, of no feature: of every fold, the mean of the others by their [weights]. */
    fun meanOutOfFold(weights: DoubleArray): Double {
        if (rows.count < FOLDS) return Double.NaN
        val sums = DoubleArray(FOLDS)
        val folds = DoubleArray(FOLDS)
        for (i in 0 until rows.count) {
            sums[foldOf(i)] += weights[i] * rows.y[i]
            folds[foldOf(i)] += weights[i]
        }
        var sum = 0.0
        for (i in 0 until rows.count) {
            val f = foldOf(i)
            val mean = (sums.sum() - sums[f]) / (folds.sum() - folds[f])
            sum += (rows.y[i] - mean).let { it * it }
        }
        return sqrt(sum / rows.count)
    }

    /** The fold an example is in: its place spread by a hash, the same at every fitting. */
    private fun foldOf(i: Int): Int = Math.floorMod(i * SPREAD, FOLDS)

    /**
     * The solution of the examples weighed by [weights], each feature held by its [lambda], begun at [from] — of none,
     * at their weighted mean. Done when the residual, each feature's by the root of its diagonal, is [TOLERANCE] of
     * what the examples' spread about their mean asks of it: their level, the mean's, would drown the authors' steps
     * (2026-11-20: the steps of the authors solved to nought so, their variance with them).
     */
    fun solve(weights: DoubleArray, lambda: DoubleArray, from: DoubleArray?): DoubleArray {
        gram.weigh(weights)
        val n = rows.features
        val diagonal = rows.diagonal(weights).also { d -> for (f in 0 until n) d[f] += lambda[f] }
        val b = rows.right(weights)
        val mean = b[0] / diagonal[0].coerceAtLeast(Double.MIN_VALUE)
        val spread = rows.scattered { i -> weights[i] * (rows.y[i] - mean) }
        val enough = TOLERANCE * sqrt(scaled(spread, spread, diagonal))
        val x = from?.copyOf() ?: DoubleArray(n).also { it[0] = mean }
        val r = times(x, lambda).let { ax -> DoubleArray(n) { b[it] - ax[it] } }
        val z = DoubleArray(n) { if (diagonal[it] > 0) r[it] / diagonal[it] else 0.0 }
        val p = z.copyOf()
        var rz = dot(r, z)
        var steps = 0
        while (steps++ < MOST_STEPS && sqrt(rz) > enough) {
            val ap = times(p, lambda)
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
        return x
    }

    /** aᵀ D⁻¹ b: every feature's by its [diagonal]. */
    private fun scaled(a: DoubleArray, b: DoubleArray, diagonal: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) if (diagonal[i] > 0) sum += a[i] * b[i] / diagonal[i]
        return sum
    }

    /** (Aᵀ W A + Λ) [x]. */
    private fun times(x: DoubleArray, lambda: DoubleArray): DoubleArray =
        gram.times(x).also { out ->
            for (f in out.indices) out[f] += lambda[f] * x[f]
        }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private companion object {
        const val FOLDS = 10

        /** The residual of the solution, of what the examples' spread asks: far below what the model's error tells. */
        const val TOLERANCE = 1e-4

        /** The steps a solution may take at most: of a well-preconditioned system, never near. */
        const val MOST_STEPS = 5000

        /** An odd number spreading the examples' places over the folds. */
        const val SPREAD = 0x9E3779B1.toInt()
    }
}
