package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import java.util.stream.IntStream
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * THE MOVES OF EVERY MODEL'S POINT ([EnsemblePoint]) BY ITS GRADIENT, a step after another — Adam (Kingma and Ba):
 * every number moved by its slope's running mean over the root of its square's, so a weight of a feature few items have
 * moves as far as one of a feature of every item, and the noise of a portion's gradient against the whole's averages
 * out over the steps. The two running means, of every number of every model, kept beside the point for the fitting.
 */
internal class Adam(private val at: EnsemblePoint) {
    private val first = at.point.map { Floats(it.count) }
    private val second = at.point.map { Floats(it.count) }
    private var steps = 0

    /**
     * Every model moved by the gradient at its point, by the [rate]: a model whose loss of [losses] is no number a
     * float holds — a point past where the function is — stays where it is.
     */
    fun step(losses: FloatArray, rate: Float) {
        steps++
        val firstBias = 1 - BETA1.pow(steps)
        val secondBias = 1 - BETA2.pow(steps)
        // The rate with both means' starting bias taken out, as Adam's
        val size = (rate * sqrt(secondBias) / firstBias).toFloat()
        IntStream.range(0, at.count).parallel().forEach { model ->
            if (!losses[model].isFinite()) return@forEach
            for (q in at.point.indices) {
                val n = at.sizes[q]
                move(at.point[q], at.gradient[q], first[q], second[q], model * n, n, size)
            }
        }
    }

    /** [n] numbers of [x] from [from] moved by their slopes [g], their running means [m] and [v] with them. */
    private fun move(x: Floats, g: Floats, m: Floats, v: Floats, from: Int, n: Int, size: Float) {
        val b1 = BETA1.toFloat()
        val b2 = BETA2.toFloat()
        for (k in from until from + n) {
            val slope = g[k]
            val mean = b1 * m[k] + (1 - b1) * slope
            val square = b2 * v[k] + (1 - b2) * slope * slope
            m[k] = mean
            v[k] = square
            x[k] = x[k] - size * mean / (sqrt(square) + EPSILON)
        }
    }

    private companion object {
        /** How fast the running means forget: Adam's own, of the slope and of its square. */
        const val BETA1 = 0.9
        const val BETA2 = 0.999

        /** Added to a root of no square: no number moved by a division by nought. */
        const val EPSILON = 1e-8f
    }
}
