package io.github.vlsergey.recommend4me.size

import kotlin.math.sqrt

/**
 * THE WEIGHED RIDGE OF THE WORDS TO A CHARACTER SOLVED: the normal equations (Aᵀ W A + Λ) x = Aᵀ W y of the examples'
 * [rows] by conjugate gradients, preconditioned by the matrix's diagonal, till the residual is [TOLERANCE] of the right
 * side's — not a number of steps — the product with the matrix of the [gram], on the card or the processor; the penalty
 * of every feature [lambda]. The folds of the cross-validation each begun of the solution of every example.
 */
internal class SizeRidge(private val rows: SizeRows, private val lambda: DoubleArray, private val gram: SizeGram) {

    /** The weights of every example; [from] the solution to begin at. */
    fun fit(from: DoubleArray? = null): DoubleArray = solve(rows.weight, from)

    /**
     * How far from the truth the logarithm of the ratio is told of an example the model did not see — the mean square
     * of the folds' errors, its root —: every fold's model begun at [whole], the solution of every example.
     */
    fun outOfFold(whole: DoubleArray): Double {
        if (rows.count < FOLDS) return Double.NaN
        var sum = 0.0
        for (fold in 0 until FOLDS) {
            val x = solve(DoubleArray(rows.count) { if (foldOf(it) == fold) 0.0 else rows.weight[it] }, whole)
            for (i in 0 until rows.count) if (foldOf(i) == fold) sum += (rows.y[i] - rows.dot(i, x)).let { it * it }
        }
        return sqrt(sum / rows.count)
    }

    /** The same of the mean alone, of no feature: of every fold, the weighted mean of the others. */
    fun meanOutOfFold(): Double {
        if (rows.count < FOLDS) return Double.NaN
        val sums = DoubleArray(FOLDS)
        val weights = DoubleArray(FOLDS)
        for (i in 0 until rows.count) {
            sums[foldOf(i)] += rows.weight[i] * rows.y[i]
            weights[foldOf(i)] += rows.weight[i]
        }
        var sum = 0.0
        for (i in 0 until rows.count) {
            val f = foldOf(i)
            val mean = (sums.sum() - sums[f]) / (weights.sum() - weights[f])
            sum += (rows.y[i] - mean).let { it * it }
        }
        return sqrt(sum / rows.count)
    }

    /** The fold an example is in: its place spread by a hash, the same at every fitting. */
    private fun foldOf(i: Int): Int = Math.floorMod(i * SPREAD, FOLDS)

    /** The solution of the examples weighed by [weights], begun at [from]. */
    private fun solve(weights: DoubleArray, from: DoubleArray?): DoubleArray {
        gram.weigh(weights)
        val n = rows.features
        val diagonal = rows.diagonal(weights).also { d -> for (f in 0 until n) d[f] += lambda[f] }
        val b = rows.right(weights)
        val x = from?.copyOf() ?: DoubleArray(n)
        val r = if (from == null) b.copyOf() else times(x).let { ax -> DoubleArray(n) { b[it] - ax[it] } }
        val z = DoubleArray(n) { r[it] / diagonal[it] }
        val p = z.copyOf()
        var rz = dot(r, z)
        val enough = TOLERANCE * sqrt(dot(b, b))
        var steps = 0
        while (steps++ < MOST_STEPS && sqrt(dot(r, r)) > enough) {
            val ap = times(p)
            val alpha = rz / dot(p, ap)
            for (f in 0 until n) {
                x[f] += alpha * p[f]
                r[f] -= alpha * ap[f]
                z[f] = r[f] / diagonal[f]
            }
            val next = dot(r, z)
            val beta = next / rz
            for (f in 0 until n) p[f] = z[f] + beta * p[f]
            rz = next
        }
        return x
    }

    /** (Aᵀ W A + Λ) [x]. */
    private fun times(x: DoubleArray): DoubleArray =
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

        /** The residual of the solution, of the right side's length: far below what the model's error tells. */
        const val TOLERANCE = 1e-8

        /** The steps a solution may take at most: of a well-preconditioned system, never near. */
        const val MOST_STEPS = 5000

        /** An odd number spreading the examples' places over the folds. */
        const val SPREAD = 0x9E3779B1.toInt()
    }
}
