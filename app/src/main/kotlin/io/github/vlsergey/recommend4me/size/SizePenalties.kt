package io.github.vlsergey.recommend4me.size

import kotlin.math.exp
import kotlin.math.ln

/**
 * THE VARIANCE OF EVERY KIND OF STEP CHOSEN BY THE ERROR ON EXAMPLES HELD OUT: the model fitted without the first fold
 * of the examples ([SizeRidge.withoutHeld]), and every kind's variance — the penalty of its inverse — moved in turns,
 * the others standing, by a factor while the error of the fold held out falls by [CHANGE] of it (owner, 2026-11-20: a
 * change of half a percent), then by the factor's root while it does; every kind's in turn again till a whole round
 * lowers it no more. What the model is to be right of, chosen of how right it is: no variance of a formula that leaves
 * out how the features go together.
 */
internal class SizePenalties(
    private val rows: SizeRows,
    private val kinds: IntArray,
    private val kindCount: Int,
    private val ridge: SizeRidge,
) {
    /** The kinds that have a feature. */
    private val present = BooleanArray(kindCount).also { p -> kinds.forEach { if (it >= 0) p[it] = true } }

    /**
     * Of every kind its variance chosen of the examples' [weights], begun at [tau]; the steps solved begun at [from].
     */
    fun chosen(weights: DoubleArray, tau: DoubleArray, from: DoubleArray?): DoubleArray {
        val training = ridge.withoutHeld(weights)
        val chosen = tau.copyOf()
        var x = from
        fun error(of: DoubleArray): Double {
            x =
                ridge.solve(
                    training,
                    DoubleArray(rows.features) { f -> if (kinds[f] < 0) 0.0 else 1 / of[kinds[f]] },
                    x,
                )
            return ridge.heldError(x!!)
        }
        var best = error(chosen)
        for (round in 1..MOST_ROUNDS) {
            val before = best
            for (k in 0 until kindCount) if (present[k]) best = along(k, chosen, best, ::error)
            if (before - best <= CHANGE * before) break
        }
        return chosen
    }

    /**
     * The kind [k]'s variance of [chosen] moved by [FACTOR] while that lowers the [error] by [CHANGE] of it, then by
     * its root while that does; the error then.
     */
    private fun along(k: Int, chosen: DoubleArray, error: Double, of: (DoubleArray) -> Double): Double {
        var best = error
        var at = ln(chosen[k])
        fun tried(a: Double) = of(chosen.copyOf().also { it[k] = exp(a) })
        var step = ln(FACTOR)
        while (true) {
            val up = tried(at + step)
            val down = if (up < best * (1 - CHANGE)) Double.MAX_VALUE else tried(at - step)
            if (minOf(up, down) < best * (1 - CHANGE)) {
                at += if (up <= down) step else -step
                best = minOf(up, down)
            } else if (step == ln(FACTOR)) step /= 2 else break
        }
        chosen[k] = exp(at)
        return best
    }

    private companion object {
        /** How little the error is to fall to move on: half a percent of it. */
        const val CHANGE = 0.005

        /** The factor a variance is moved by first: a step of the search, not of the model. */
        const val FACTOR = 4.0

        /** The rounds of every kind at most. */
        const val MOST_ROUNDS = 10
    }
}
