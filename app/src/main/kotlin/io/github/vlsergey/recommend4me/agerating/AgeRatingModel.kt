package io.github.vlsergey.recommend4me.agerating

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a work tells of its age rating for the model: its [features] — its tags, the site it is of —
 * and the steps of the scale its rating is known to lie between, both in ([low], [high]: one step of
 * a rating stated, a few of a bound).
 */
class RatingExample(val features: List<String>, val low: Int, val high: Int)

/**
 * THE AGE RATING OF A WORK BY WHAT IS KNOWN OF IT — an ordinal logistic model: a work's features
 * add up to how strong it is ([weights], a feature of none nought), and the chance its rating is at
 * most the step k of the scale is σ(threshold_k − that sum), the [thresholds] rising between the
 * [steps] ratings. Learnt from works whose rating is known to lie between two steps — the chance of
 * that span — so a bound a site tells ("18+": NC-17 to NC-21) teaches it as a rating it states does.
 */
class AgeRatingModel(val weights: Map<String, Double>, val thresholds: DoubleArray) {
    val steps: Int get() = thresholds.size + 1

    /** How strong a work of these features is. */
    fun strength(features: Collection<String>): Double = features.sumOf { weights[it] ?: 0.0 }

    /** The chance of every rating of the scale, from the mildest, of a work of these features. */
    fun chances(features: Collection<String>): DoubleArray {
        val s = strength(features)
        val atMost = DoubleArray(steps) { k -> if (k == steps - 1) 1.0 else sigmoid(thresholds[k] - s) }
        return DoubleArray(steps) { k -> max(0.0, atMost[k] - if (k == 0) 0.0 else atMost[k - 1]) }
    }

    companion object {
        /** The penalty of every weight, against the loss summed over the works: a feature of a few works stays near nought. */
        const val LAMBDA = 1.0

        private const val ITERATIONS = 400
        private const val RATE = 0.05

        /** The least a threshold rises over the one below it. */
        private const val GAP = 1e-3

        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        /**
         * The model of the scale of [steps] ratings learnt from the [examples], by Adam on the loss
         * summed with the penalty; null when no example bounds the rating at all.
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
            for (step in 1..ITERATIONS) {
                grad.fill(0.0)
                for (i in useful.indices) {
                    val e = useful[i]
                    val row = rows[i]
                    var s = 0.0
                    for (j in row) s += theta[j]
                    // The chance of the span [low, high] and its slopes: F(high) − F(low − 1), F(k) = σ(θ_k − s)
                    val hi = if (e.high < k) sigmoid(theta[n + e.high] - s) else 1.0
                    val lo = if (e.low > 0) sigmoid(theta[n + e.low - 1] - s) else 0.0
                    val p = max(hi - lo, 1e-12)
                    val dHi = if (e.high < k) hi * (1 - hi) else 0.0
                    val dLo = if (e.low > 0) lo * (1 - lo) else 0.0
                    // Of −log p: the slope by the strength, and by the thresholds of the span's ends
                    val ds = (dHi - dLo) / p
                    for (j in row) grad[j] += ds
                    if (e.high < k) grad[n + e.high] -= dHi / p
                    if (e.low > 0) grad[n + e.low - 1] += dLo / p
                }
                for (j in 0 until n) grad[j] += LAMBDA * theta[j]
                for (j in theta.indices) {
                    m[j] = 0.9 * m[j] + 0.1 * grad[j]
                    v[j] = 0.999 * v[j] + 0.001 * grad[j] * grad[j]
                    val mh = m[j] / (1 - Math.pow(0.9, step.toDouble()))
                    val vh = v[j] / (1 - Math.pow(0.999, step.toDouble()))
                    theta[j] -= RATE * mh / (sqrt(vh) + 1e-8)
                }
                // The thresholds kept rising
                for (j in 1 until k) theta[n + j] = max(theta[n + j], theta[n + j - 1] + GAP)
            }
            return AgeRatingModel(features.withIndex().associate { (i, f) -> f to theta[i] }, DoubleArray(k) { theta[n + it] })
        }

        /** The loss of the [model] on the [examples]: the mean −log of the chance of their spans. */
        fun loss(model: AgeRatingModel, examples: List<RatingExample>): Double = examples.map { e ->
            val p = model.chances(e.features)
            -ln(max(1e-12, (e.low..e.high).sumOf { p[it] }))
        }.average()
    }
}
