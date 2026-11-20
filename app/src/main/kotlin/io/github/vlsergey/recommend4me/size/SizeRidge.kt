package io.github.vlsergey.recommend4me.size

import kotlin.math.sqrt

/**
 * THE WEIGHED RIDGE OF THE WORDS TO A CHARACTER SOLVED of the weights and the penalties asked, by the [solver] — on the
 * card or the processor —, till the residual is [TOLERANCE] of what the spread asks, not a number of steps; the error
 * of a solution told on a tenth of the examples held out of it.
 */
internal class SizeRidge(private val rows: SizeRows, private val solver: SizeSolver) {

    /** The solutions solved, and the steps of all of them: what a fitting cost, told in the log. */
    var solutions = 0
        private set

    var steps = 0
        private set

    /** The time the solutions took, in nanoseconds. */
    var nanos = 0L
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
     * at their weighted mean — by the [solver], done at [TOLERANCE]; counted, and its time.
     */
    fun solve(weights: DoubleArray, lambda: DoubleArray, from: DoubleArray?): DoubleArray {
        val began = System.nanoTime()
        val solved = solver.solve(weights, lambda, from, TOLERANCE, MOST_STEPS)
        nanos += System.nanoTime() - began
        solutions++
        steps += solved.steps
        return solved.x
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
