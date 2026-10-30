package io.github.vlsergey.recommend4me.duplicate

import kotlin.math.abs
import kotlin.math.exp

/**
 * THE CHANCE A PAIR IS ONE WORK, by the user's answers: a logistic model of how alike each layer of
 * the two is ([DuplicateCandidate]) — the titles, the descriptions, the size, and every facet the sites
 * share apart (the universes, the characters, the tags, the status...) — and whether both have the
 * layer at all, fitted on the pairs the user said are one work and those said to be different
 * ([fit]): which facet tells a pair is one work, and how much, is learnt. [weights] by the name of the
 * feature ([TITLE], [facet]...); null — no answer that any pair is one work yet: the mean of the
 * layers both have stands for the chance, the facets' mean one layer of them.
 */
class DuplicateModel(val weights: Map<String, Double>?) {

    /** The chance the pair of these layers is one work. */
    fun chance(title: Float, description: Float?, size: Float?, facets: Map<String, Float>): Double {
        val w = weights ?: return listOfNotNull(title, description, size, facets.values.takeIf { it.isNotEmpty() }?.average()?.toFloat())
            .map { it.toDouble() }.average()
        var z = 0.0
        features(title, description, size, facets).forEach { (name, x) -> z += (w[name] ?: 0.0) * x }
        return sigmoid(z)
    }

    fun chance(c: DuplicateCandidate): Double = chance(c.title, c.description, c.size, c.facets)

    /**
     * The model as the search weighs pair after pair of it: the [families] of facets by their places,
     * no map made of a pair. Of a learnt model the logit — as the chance orders the pairs — else the mean.
     */
    inner class Scorer(families: List<String>) {
        private val constant = weights?.get(CONSTANT) ?: 0.0
        private val title = weights?.get(TITLE) ?: 0.0
        private val description = weights?.get(DESCRIPTION) ?: 0.0
        private val described = weights?.get(has(DESCRIPTION)) ?: 0.0
        private val size = weights?.get(SIZE) ?: 0.0
        private val sized = weights?.get(has(SIZE)) ?: 0.0
        private val facet = DoubleArray(families.size) { weights?.get(facet(families[it])) ?: 0.0 }
        private val faceted = DoubleArray(families.size) { weights?.get(has(facet(families[it]))) ?: 0.0 }

        /** The facets both have: the bits of [both]; of the f-th, how alike they are [valued][f][j]. */
        fun score(t: Float, d: Float, hasDescription: Boolean, s: Float, hasSize: Boolean, both: Long, valued: Array<FloatArray>, j: Int): Double {
            if (weights == null) {
                var sum = t.toDouble()
                var layers = 1
                if (hasDescription) {
                    sum += d
                    layers++
                }
                if (hasSize) {
                    sum += s
                    layers++
                }
                if (both != 0L) {
                    var f = 0.0
                    var n = 0
                    var bits = both
                    while (bits != 0L) {
                        val k = java.lang.Long.numberOfTrailingZeros(bits)
                        f += valued[k][j]
                        n++
                        bits = bits and (bits - 1L)
                    }
                    sum += f / n
                    layers++
                }
                return sum / layers
            }
            var z = constant + title * t
            if (hasDescription) z += description * d + described
            if (hasSize) z += size * s + sized
            var bits = both
            while (bits != 0L) {
                val k = java.lang.Long.numberOfTrailingZeros(bits)
                z += facet[k] * valued[k][j] + faceted[k]
                bits = bits and (bits - 1L)
            }
            return z
        }
    }

    companion object {
        const val CONSTANT = "constant"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val SIZE = "size"

        /** The feature of how alike the values of a facet the sites share are: "facet:<facet>". */
        fun facet(family: String) = "facet:$family"

        /** The feature of whether both have the layer: "<layer>?". */
        fun has(feature: String) = "$feature?"

        /** How far the weights are held to nought: the user's few answers do not swing them wide. */
        private const val PENALTY = 1.0

        private const val STEPS = 50

        /** The features of a pair by their names: the constant, every layer both have and that they have it. */
        fun features(title: Float, description: Float?, size: Float?, facets: Map<String, Float>): Map<String, Double> {
            val out = HashMap<String, Double>()
            out[CONSTANT] = 1.0
            out[TITLE] = title.toDouble()
            description?.let {
                out[DESCRIPTION] = it.toDouble()
                out[has(DESCRIPTION)] = 1.0
            }
            size?.let {
                out[SIZE] = it.toDouble()
                out[has(SIZE)] = 1.0
            }
            facets.forEach { (family, v) ->
                out[facet(family)] = v.toDouble()
                out[has(facet(family))] = 1.0
            }
            return out
        }

        fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

        /**
         * The model of the [pairs] by the user's word on each ([said]: true — one work, false —
         * different, null — not said): a pair said one work weighs 1, labelled 1; one said different
         * weighs 1, labelled 0; a pair not answered on — nearly every pair the search keeps is two
         * works — labelled 0 and weighing as much as an unanswered one is likely different, by the rule
         * of succession over the answers: (different + 1) / (answered + 2). Its features every one any
         * pair has. Newton's steps of the penalized log-likelihood, the constant not penalized. Null when
         * no pair is said to be one work.
         */
        fun fit(pairs: List<DuplicateCandidate>, said: (DuplicateCandidate) -> Boolean?): DuplicateModel? {
            val labels = pairs.map(said)
            val same = labels.count { it == true }
            if (same == 0) return null
            val different = labels.count { it == false }
            val lack = (different + 1.0) / (same + different + 2.0)
            val names = (listOf(CONSTANT, TITLE, DESCRIPTION, has(DESCRIPTION), SIZE, has(SIZE)) +
                pairs.flatMap { it.facets.keys }.distinct().sorted().flatMap { listOf(facet(it), has(facet(it))) })
            val at = names.withIndex().associate { it.value to it.index }
            val count = names.size
            // Every pair's features as the places and the values of those it has
            val places = ArrayList<IntArray>(pairs.size)
            val values = ArrayList<DoubleArray>(pairs.size)
            pairs.forEach { c ->
                val own = features(c.title, c.description, c.size, c.facets)
                places += IntArray(own.size)
                values += DoubleArray(own.size)
                own.entries.forEachIndexed { k, (name, x) ->
                    places.last()[k] = at.getValue(name)
                    values.last()[k] = x
                }
            }
            val w = DoubleArray(count)
            repeat(STEPS) {
                val gradient = DoubleArray(count)
                val hessian = Array(count) { DoubleArray(count) }
                for (i in pairs.indices) {
                    val weight = if (labels[i] == null) lack else 1.0
                    val y = if (labels[i] == true) 1.0 else 0.0
                    val p = places[i]
                    val x = values[i]
                    var z = 0.0
                    for (k in p.indices) z += w[p[k]] * x[k]
                    val chance = sigmoid(z)
                    val r = weight * chance * (1 - chance)
                    for (k in p.indices) {
                        gradient[p[k]] += weight * (chance - y) * x[k]
                        for (q in p.indices) hessian[p[k]][p[q]] += r * x[k] * x[q]
                    }
                }
                for (k in 1 until count) {
                    gradient[k] += PENALTY * w[k]
                    hessian[k][k] += PENALTY
                }
                // A feature no pair has: its weight held where it is
                for (k in 0 until count) if (hessian[k][k] == 0.0) hessian[k][k] = 1.0
                val step = solve(hessian, gradient)
                var moved = 0.0
                for (k in 0 until count) {
                    w[k] -= step[k]
                    moved = maxOf(moved, abs(step[k]))
                }
                if (moved < 1e-6) return DuplicateModel(names.indices.associate { names[it] to w[it] })
            }
            return DuplicateModel(names.indices.associate { names[it] to w[it] })
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
