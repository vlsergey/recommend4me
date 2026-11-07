package io.github.vlsergey.recommend4me.duplicate

import kotlin.math.abs
import kotlin.math.exp

/**
 * THE CHANCE A PAIR IS OF A KIND, by the user's answers: a logistic model of how alike each layer of the two is
 * ([PairLayers]) — the titles, the descriptions, the size, and every facet the sites share apart (the universes, the
 * characters, the tags, the status...) — whether both have the layer at all, and whether the dictionary of authors
 * gives them one author, fully or in part, fitted on the pairs the user said are of the kind and those said not to be
 * ([fit]): which facet tells it, and how much, is learnt. [weights] by the name of the feature ([TITLE], [facet]...);
 * null — the model before any answer ([LogisticPairModel.prior]): the mean of the layers both have stands for the
 * chance, the facets' mean one layer of them.
 */
internal class Logistic(val weights: Map<String, Double>?) : FittedPairModel {

    override fun chance(layers: PairLayers): Double {
        val w =
            weights
                ?: return listOfNotNull(
                        layers.title,
                        layers.description,
                        layers.size,
                        layers.facets.values.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    )
                    .map { it.toDouble() }
                    .average()
        var z = 0.0
        features(layers).forEach { (name, x) -> z += (w[name] ?: 0.0) * x }
        return sigmoid(z)
    }

    /**
     * The weights by name, a line each: "<name>=<weight>" — the text the application kept of the model before it came
     * from a plugin.
     */
    override fun pack(): ByteArray =
        weights.orEmpty().entries.joinToString("\n") { (name, w) -> "$name=$w" }.toByteArray(Charsets.UTF_8)

    /**
     * The model as a search weighs millions of pairs: of the [families] of facets by their places, its weights laid
     * out, no map made of a pair ([Laid]).
     */
    fun laid(families: List<String>): Laid =
        Laid(
            mean = weights == null,
            constant = weights?.get(CONSTANT) ?: 0.0,
            title = weights?.get(TITLE) ?: 0.0,
            description = weights?.get(DESCRIPTION) ?: 0.0,
            described = weights?.get(has(DESCRIPTION)) ?: 0.0,
            size = weights?.get(SIZE) ?: 0.0,
            sized = weights?.get(has(SIZE)) ?: 0.0,
            author = weights?.get(SAME_AUTHOR) ?: 0.0,
            facet = DoubleArray(families.size) { weights?.get(facet(families[it])) ?: 0.0 },
            faceted = DoubleArray(families.size) { weights?.get(has(facet(families[it]))) ?: 0.0 },
        )

    /**
     * A model's weights by the places of the facets: what a pair is scored by in a search — of a learnt model the
     * logit, as the chance orders the pairs, else ([mean]) the mean of the layers both have.
     */
    class Laid(
        val mean: Boolean,
        val constant: Double,
        val title: Double,
        val description: Double,
        val described: Double,
        val size: Double,
        val sized: Double,
        val author: Double,
        val facet: DoubleArray,
        val faceted: DoubleArray,
    ) {
        /** The facets both have: the bits of [both]; of the f-th, how alike they are [valued] (f). */
        fun score(
            t: Float,
            d: Float,
            hasDescription: Boolean,
            s: Float,
            hasSize: Boolean,
            both: Long,
            valued: (Int) -> Float,
            sameAuthor: Boolean,
        ): Double {
            if (mean) {
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
                        f += valued(java.lang.Long.numberOfTrailingZeros(bits))
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
            if (sameAuthor) z += author
            var bits = both
            while (bits != 0L) {
                val k = java.lang.Long.numberOfTrailingZeros(bits)
                z += facet[k] * valued(k) + faceted[k]
                bits = bits and (bits - 1L)
            }
            return z
        }

        /**
         * The numbers as the card's kernel reads them, one model after another: the mean's mark, the constant, the
         * title, the description and that both have one, the size and that both have it, one author, then of every
         * facet its weight and that both have it.
         */
        fun flat(): FloatArray =
            floatArrayOf(
                if (mean) 1f else 0f,
                constant.toFloat(),
                title.toFloat(),
                description.toFloat(),
                described.toFloat(),
                size.toFloat(),
                sized.toFloat(),
                author.toFloat(),
            ) + FloatArray(facet.size * 2) { if (it % 2 == 0) facet[it / 2].toFloat() else faceted[it / 2].toFloat() }
    }

    companion object {
        const val CONSTANT = "constant"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val SIZE = "size"

        /** The feature of the dictionary of authors giving the two one author, fully or in part. */
        const val SAME_AUTHOR = "sameAuthor"

        /** The numbers of a model before its facets' in [Laid.flat]. */
        const val FLAT_HEAD = 8

        /** The feature of how alike the values of a facet the sites share are: "facet:<facet>". */
        fun facet(family: String) = "facet:$family"

        /** The feature of whether both have the layer: "<layer>?". */
        fun has(feature: String) = "$feature?"

        /** How far the weights are held to nought: the user's few answers do not swing them wide. */
        private const val PENALTY = 1.0

        private const val STEPS = 50

        /** The features of a pair by their names: the constant, every layer both have and that they have it. */
        fun features(layers: PairLayers): Map<String, Double> {
            val out = HashMap<String, Double>()
            out[CONSTANT] = 1.0
            out[TITLE] = layers.title.toDouble()
            if (layers.sameAuthor) out[SAME_AUTHOR] = 1.0
            layers.description?.let {
                out[DESCRIPTION] = it.toDouble()
                out[has(DESCRIPTION)] = 1.0
            }
            layers.size?.let {
                out[SIZE] = it.toDouble()
                out[has(SIZE)] = 1.0
            }
            layers.facets.forEach { (family, v) ->
                out[facet(family)] = v.toDouble()
                out[has(facet(family))] = 1.0
            }
            return out
        }

        fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

        /**
         * The model of the [pairs] by the user's word on each ([said]: true — of the kind, false — not, null — not
         * said): a pair said of the kind weighs 1, labelled 1; one said not weighs 1, labelled 0; a pair not answered
         * on — nearly every pair a search keeps is not of the kind — labelled 0 and weighing as much as an unanswered
         * one is likely not of it, by the rule of succession over the answers: (not + 1) / (answered + 2). Its features
         * every one any pair has. Newton's steps of the penalized log-likelihood, the constant not penalized. Null when
         * no pair is said to be of the kind.
         */
        fun fit(pairs: List<PairLayers>, said: List<Boolean?>): Logistic? {
            require(said.size == pairs.size) { "${said.size} words on ${pairs.size} pairs" }
            val yes = said.count { it == true }
            if (yes == 0) return null
            val no = said.count { it == false }
            val lack = (no + 1.0) / (yes + no + 2.0)
            val names =
                (listOf(CONSTANT, TITLE, DESCRIPTION, has(DESCRIPTION), SIZE, has(SIZE), SAME_AUTHOR) +
                    pairs.flatMap { it.facets.keys }.distinct().sorted().flatMap { listOf(facet(it), has(facet(it))) })
            val at = names.withIndex().associate { it.value to it.index }
            val count = names.size
            // Every pair's features as the places and the values of those it has
            val places = ArrayList<IntArray>(pairs.size)
            val values = ArrayList<DoubleArray>(pairs.size)
            pairs.forEach { c ->
                val own = features(c)
                places += IntArray(own.size)
                values += DoubleArray(own.size)
                own.entries.forEachIndexed { k, (name, x) ->
                    places.last()[k] = at.getValue(name)
                    values.last()[k] = x
                }
            }
            val w = DoubleArray(count)
            var steps = 0
            var moved = Double.POSITIVE_INFINITY
            while (steps++ < STEPS && moved >= MOVED) moved = step(w, places, values, said, lack)
            return Logistic(names.indices.associate { names[it] to w[it] })
        }

        /**
         * One Newton's step of the weights [w] of the pairs' features ([places], [values]) by the user's [said], a pair
         * not answered weighing [lack]: how far the weights moved, the most of any.
         */
        private fun step(
            w: DoubleArray,
            places: List<IntArray>,
            values: List<DoubleArray>,
            said: List<Boolean?>,
            lack: Double,
        ): Double {
            val count = w.size
            val gradient = DoubleArray(count)
            val hessian = Array(count) { DoubleArray(count) }
            for (i in places.indices) {
                val weight = if (said[i] == null) lack else 1.0
                val y = if (said[i] == true) 1.0 else 0.0
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
            return moved
        }

        /** A step of the weights small enough for the fitting to end. */
        private const val MOVED = 1e-6

        /** A pivot nearer nought than this leaves its weight where it is. */
        private const val SINGULAR = 1e-12

        /** The solution of a x = b by Gauss's elimination with the largest pivot: a small system. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (c in 0 until n) {
                val pivot = (c until n).maxBy { abs(m[it][c]) }
                val t = m[c]
                m[c] = m[pivot]
                m[pivot] = t
                if (abs(m[c][c]) < SINGULAR) continue
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = m[r][c] / m[c][c]
                    if (f != 0.0) for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { if (abs(m[it][it]) < SINGULAR) 0.0 else m[it][n] / m[it][it] }
        }

        /** The weights of [bytes] written by [pack]; null of bytes of no weight. */
        fun read(bytes: ByteArray): Logistic? {
            val weights =
                String(bytes, Charsets.UTF_8)
                    .lines()
                    .filter { '=' in it }
                    .associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toDouble() }
            return if (weights.isEmpty()) null else Logistic(weights)
        }
    }
}
