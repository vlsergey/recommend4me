package io.github.vlsergey.recommend4me.agerating

import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a work tells of its age rating for the model: its [features] — its tags, the site it is of — and the steps of
 * the scale its rating is known to lie between, both in ([low], [high]: one step of a rating stated, a few of a bound).
 */
class RatingExample(val features: List<String>, val low: Int, val high: Int)

/**
 * THE AGE RATING OF A WORK BY WHAT IS KNOWN OF IT — an ordinal logistic model: a work's features add up to how strong
 * it is ([weights], a feature of none nought), and the chance its rating is at most the step k of the scale is
 * σ(threshold_k − that sum), the [thresholds] rising between the [steps] ratings. Learnt from works whose rating is
 * known to lie between two steps — the chance of that span — so a bound a site tells ("18+": NC-17 to NC-21) teaches it
 * as a rating it states does.
 */
class AgeRatingModel(val weights: Map<String, Double>, val thresholds: DoubleArray) {
    val steps: Int
        get() = thresholds.size + 1

    /** How strong a work of these features is. */
    fun strength(features: Collection<String>): Double = features.sumOf { weights[it] ?: 0.0 }

    /** The chance of every rating of the scale, from the mildest, of a work of these features. */
    fun chances(features: Collection<String>): DoubleArray {
        val s = strength(features)
        val atMost = DoubleArray(steps) { k -> if (k == steps - 1) 1.0 else sigmoid(thresholds[k] - s) }
        return DoubleArray(steps) { k -> max(0.0, atMost[k] - if (k == 0) 0.0 else atMost[k - 1]) }
    }

    companion object {
        /**
         * The penalty of every weight, against the loss summed over the works: a feature of a few works stays near
         * nought.
         */
        const val LAMBDA = 1.0

        private const val ITERATIONS = 400
        private const val RATE = 0.05

        /** The works a thread sums the slopes of at a time: work enough a task, not meaning. */
        private const val CHUNK = 4096

        /** The least a threshold rises over the one below it. */
        private const val GAP = 1e-3

        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        /**
         * The model of the scale of [steps] ratings learnt from the [examples], by Adam on the loss summed with the
         * penalty; null when no example bounds the rating at all.
         */
        fun fit(examples: List<RatingExample>, steps: Int): AgeRatingModel? {
            val useful = examples.filter { it.low > 0 || it.high < steps - 1 }
            if (useful.isEmpty()) return null
            val features = useful.flatMap { it.features }.distinct()
            val index = features.withIndex().associate { (i, f) -> f to i }
            val rows = useful.map { e -> e.features.map { index.getValue(it) }.distinct().toIntArray() }
            val n = features.size
            val k = steps - 1
            // The weights, then the thresholds, spread at first
            val theta = DoubleArray(n + k) { if (it < n) 0.0 else (it - n - (k - 1) / 2.0) }
            val m = DoubleArray(theta.size)
            val v = DoubleArray(theta.size)
            val grad = DoubleArray(theta.size)
            val chunks = (useful.size + CHUNK - 1) / CHUNK
            for (step in 1..ITERATIONS) {
                grad.fill(0.0)
                // The works summed in chunks at once, the chunks' slopes added after: the last digits alone
                IntStream.range(0, chunks)
                    .parallel()
                    .mapToObj { c ->
                        DoubleArray(theta.size).also { part ->
                            for (i in c * CHUNK until min(useful.size, (c + 1) * CHUNK)) addSlope(
                                useful[i],
                                rows[i],
                                theta,
                                n,
                                k,
                                part,
                            )
                        }
                    }
                    .toList()
                    .forEach { part -> for (j in grad.indices) grad[j] += part[j] }
                for (j in 0 until n) grad[j] += LAMBDA * theta[j]
                adamStep(theta, grad, m, v, step)
                // The thresholds kept rising
                for (j in 1 until k) theta[n + j] = max(theta[n + j], theta[n + j - 1] + GAP)
            }
            return AgeRatingModel(
                features.withIndex().associate { (i, f) -> f to theta[i] },
                DoubleArray(k) { theta[n + it] },
            )
        }

        /**
         * The slope of −log of the chance of the work [e]'s span added to [grad]: by the strength — the weights of its
         * features, [row] — and by the thresholds of the span's ends. θ is the [n] weights, then the [k] thresholds.
         */
        private fun addSlope(e: RatingExample, row: IntArray, theta: DoubleArray, n: Int, k: Int, grad: DoubleArray) {
            var s = 0.0
            for (j in row) s += theta[j]
            // The chance of the span [low, high] and its slopes: F(high) − F(low − 1), F(k) = σ(θ_k − s)
            val hi = if (e.high < k) sigmoid(theta[n + e.high] - s) else 1.0
            val lo = if (e.low > 0) sigmoid(theta[n + e.low - 1] - s) else 0.0
            val p = max(hi - lo, LEAST_CHANCE)
            val dHi = if (e.high < k) hi * (1 - hi) else 0.0
            val dLo = if (e.low > 0) lo * (1 - lo) else 0.0
            // Of −log p: the slope by the strength, and by the thresholds of the span's ends
            val ds = (dHi - dLo) / p
            for (j in row) grad[j] += ds
            if (e.high < k) grad[n + e.high] -= dHi / p
            if (e.low > 0) grad[n + e.low - 1] += dLo / p
        }

        /** Adam's step number [step] of [theta] by the slope [grad], [m] and [v] its running means. */
        private fun adamStep(theta: DoubleArray, grad: DoubleArray, m: DoubleArray, v: DoubleArray, step: Int) {
            // The corrections of the means' start at nought: of the step, the same for every weight
            val mBias = 1 - DECAY.pow(step)
            val vBias = 1 - SQUARES_DECAY.pow(step)
            for (j in theta.indices) {
                m[j] = DECAY * m[j] + NEW * grad[j]
                v[j] = SQUARES_DECAY * v[j] + NEW_SQUARE * grad[j] * grad[j]
                theta[j] -= RATE * (m[j] / mBias) / (sqrt(v[j] / vBias) + EPSILON)
            }
        }

        /** The loss of the [model] on the [examples]: the mean −log of the chance of their spans. */
        fun loss(model: AgeRatingModel, examples: List<RatingExample>): Double =
            examples
                .map { e ->
                    val p = model.chances(e.features)
                    -ln(max(LEAST_CHANCE, (e.low..e.high).sumOf { p[it] }))
                }
                .average()

        /** The least chance of a span taken: its −log stays finite. */
        private const val LEAST_CHANCE = 1e-12

        /** Adam's decay of the running mean of the slopes, and the share of the new slope in it. */
        private const val DECAY = 0.9
        private const val NEW = 0.1

        /** Adam's decay of the running mean of the slopes' squares, and the share of the new square in it. */
        private const val SQUARES_DECAY = 0.999
        private const val NEW_SQUARE = 0.001

        /** Adam's guard against a division by a mean of squares of nought. */
        private const val EPSILON = 1e-8
    }
}
