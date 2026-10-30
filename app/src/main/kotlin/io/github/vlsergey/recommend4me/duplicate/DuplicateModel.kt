package io.github.vlsergey.recommend4me.duplicate

import kotlin.math.abs
import kotlin.math.exp

/**
 * THE CHANCE A PAIR IS ONE WORK, by the user's answers: a logistic model of how alike each layer of
 * the two is ([DuplicateCandidate]) — the titles, the descriptions, the values of the facets the sites
 * share, the size — and whether they have the layer at all, fitted on the pairs the user said are one
 * work and those said to be different ([fit]). [weights] null — no answer that any pair is one work
 * yet: the mean of the layers both have stands for the chance.
 */
class DuplicateModel(val weights: DoubleArray?) {

    init {
        require(weights == null || weights.size == FEATURES) { "${weights?.size} weights of $FEATURES features" }
    }

    /** The chance the pair of these layers is one work. */
    fun chance(title: Float, description: Float?, facets: Float?, size: Float?): Double {
        val w = weights ?: return listOfNotNull(title, description, facets, size).map { it.toDouble() }.average()
        var z = 0.0
        val x = features(title, description, facets, size)
        for (k in x.indices) z += w[k] * x[k]
        return sigmoid(z)
    }

    fun chance(c: DuplicateCandidate): Double = chance(c.title, c.description, c.facets, c.size)

    companion object {
        /** The constant; the title; the description and whether both have one; the facets, the size alike. */
        const val FEATURES = 8

        /** How far the weights are held to nought: the user's few answers do not swing them wide. */
        private const val PENALTY = 1.0

        private const val STEPS = 50

        fun features(title: Float, description: Float?, facets: Float?, size: Float?): DoubleArray = doubleArrayOf(
            1.0,
            title.toDouble(),
            description?.toDouble() ?: 0.0, if (description != null) 1.0 else 0.0,
            facets?.toDouble() ?: 0.0, if (facets != null) 1.0 else 0.0,
            size?.toDouble() ?: 0.0, if (size != null) 1.0 else 0.0,
        )

        fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

        /**
         * The model of the [pairs] by the user's word on each ([said]: true — one work, false —
         * different, null — not said): a pair said one work weighs 1, labelled 1; one said different
         * weighs 1, labelled 0; a pair not answered on — nearly every pair the search keeps is two
         * works — labelled 0 and weighing as much as an unanswered one is likely different, by the rule
         * of succession over the answers: (different + 1) / (answered + 2). Newton's steps of the
         * penalized log-likelihood, the constant not penalized. Null when no pair is said to be one work.
         */
        fun fit(pairs: List<DuplicateCandidate>, said: (DuplicateCandidate) -> Boolean?): DuplicateModel? {
            val labels = pairs.map(said)
            val same = labels.count { it == true }
            if (same == 0) return null
            val different = labels.count { it == false }
            val lack = (different + 1.0) / (same + different + 2.0)
            val x = pairs.map { features(it.title, it.description, it.facets, it.size) }
            val w = DoubleArray(FEATURES)
            repeat(STEPS) {
                val gradient = DoubleArray(FEATURES)
                val hessian = Array(FEATURES) { DoubleArray(FEATURES) }
                for (i in x.indices) {
                    val weight = if (labels[i] == null) lack else 1.0
                    val y = if (labels[i] == true) 1.0 else 0.0
                    var z = 0.0
                    for (k in 0 until FEATURES) z += w[k] * x[i][k]
                    val p = sigmoid(z)
                    val r = weight * p * (1 - p)
                    for (k in 0 until FEATURES) {
                        gradient[k] += weight * (p - y) * x[i][k]
                        for (q in 0 until FEATURES) hessian[k][q] += r * x[i][k] * x[i][q]
                    }
                }
                for (k in 1 until FEATURES) {
                    gradient[k] += PENALTY * w[k]
                    hessian[k][k] += PENALTY
                }
                // A layer no pair has: its weights held where they are
                for (k in 0 until FEATURES) if (hessian[k][k] == 0.0) hessian[k][k] = 1.0
                val step = solve(hessian, gradient)
                var moved = 0.0
                for (k in 0 until FEATURES) {
                    w[k] -= step[k]
                    moved = maxOf(moved, abs(step[k]))
                }
                if (moved < 1e-6) return DuplicateModel(w)
            }
            return DuplicateModel(w)
        }

        /** The solution of a x = b by Gauss's elimination with the largest pivot: a small system. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (c in 0 until n) {
                val pivot = (c until n).maxBy { abs(m[it][c]) }
                val t = m[c]
                m[c] = m[pivot]
                m[pivot] = t
                if (abs(m[c][c]) < 1e-12) continue
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = m[r][c] / m[c][c]
                    if (f != 0.0) for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { if (abs(m[it][it]) < 1e-12) 0.0 else m[it][n] / m[it][it] }
        }
    }
}
