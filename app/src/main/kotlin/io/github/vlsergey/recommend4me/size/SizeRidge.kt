package io.github.vlsergey.recommend4me.size

import kotlin.math.sqrt

/**
 * THE WEIGHED RIDGE OF THE WORDS TO A CHARACTER SOLVED: the normal equations (Aᵀ W A + Λ) x = Aᵀ W y of the examples'
 * [rows], of the weights and the penalties asked, by conjugate gradients preconditioned by the matrix's diagonal, till
 * the residual is [TOLERANCE] of what the spread asks — not a number of steps —, the product with the matrix of the
 * [gram], on the card or the processor; the error of a solution told on a tenth of the examples held out of it.
 */
internal class SizeRidge(private val rows: SizeRows, private val gram: SizeGram) {

    /** The solutions solved, and the steps of all of them: what a fitting cost, told in the log. */
    var solutions = 0
        private set

    var steps = 0
        private set

    /**
     * The error of the mean alone on the examples of the first fold, held out — the root of their mean square —: of the
     * mean of the others by their [weights], what the model is to beat ([heldError]).
     */
    fun meanHeld(weights: DoubleArray): Double {
        var sum = 0.0
        var weight = 0.0
        for (i in 0 until rows.count) if (foldOf(i) != 0) {
            sum += weights[i] * rows.y[i]
            weight += weights[i]
        }
        val mean = if (weight > 0) sum / weight else 0.0
        var square = 0.0
        var count = 0
        for (i in 0 until rows.count) if (foldOf(i) == 0) {
            square += (rows.y[i] - mean).let { it * it }
            count++
        }
        return if (count == 0) 0.0 else sqrt(square / count)
    }

    /** The [weights] of the examples but those of the first fold, held out: what a choice is made without. */
    fun withoutHeld(weights: DoubleArray): DoubleArray =
        DoubleArray(rows.count) { if (foldOf(it) == 0) 0.0 else weights[it] }

    /** The error of the solution [x] on the examples of the first fold, held out: the root of their mean square. */
    fun heldError(x: DoubleArray): Double {
        var sum = 0.0
        var count = 0
        for (i in 0 until rows.count) if (foldOf(i) == 0) {
            sum += (rows.y[i] - rows.dot(i, x)).let { it * it }
            count++
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
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
        var taken = 0
        while (taken++ < MOST_STEPS && sqrt(rz) > enough) {
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
        solutions++
        steps += taken - 1
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
