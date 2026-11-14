package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A feature's weight in the order of what is found, of the feature read on a scale of its own: (value − mean) / scale.
 */
class FeatureWeight(val weight: Double, val mean: Double, val scale: Double)

/**
 * The works the user judged of a line, as a search found them: each one's features, and how far it answers the line.
 */
class JudgedLine(val line: String, val works: List<Pair<Map<String, Double>, SearchFit>>)

/**
 * THE ORDER OF WHAT A SEARCH FINDS, LEARNT FROM THE USER'S WORD (user, 2026-11-13): the works found put in the order of
 * a sum of their features ([SearchResult.features] — how close by meaning, how their words matched, what the reranker
 * said), each weighed as the user's judgments of the lines searched tell. A feature a work lacks is 0 of it.
 *
 * Learnt by pairs within a line ([fit]): of every line, every work judged to answer it further than another — whole
 * than in part, in part than not at all ([SearchFit]) — the first to stand higher, a logistic model of the difference
 * of their features. Only the order within a line is learnt: what a reranker gives one line and another are not on one
 * scale. Every line weighs alike, however many works it has judged; a line with no pair — every work judged alike —
 * teaches nothing of an order.
 *
 * NO FEATURE EVER WEIGHS NOUGHT, NOR AGAINST (user, 2026-11-14): every feature is of a work answering the line more —
 * closer by meaning, more of its words, a higher reranker's score —, and a few lines judged must not shut one out. Of
 * three lines the words weighed −0.97 and the meaning nought: a work of the line's own rare word in its title went to
 * the bottom of all. So the weights of n features sum to n, and every one is at least half its equal share, 1/2
 * ([descend]): the judgments say how the rest, n/2, is shared, never that a way of finding counts for nothing — nor
 * that all of them do, the sum itself let go to nought (as the first try let it, 2026-11-14).
 */
class SearchWeights(val features: Map<String, FeatureWeight>) {

    /** The work of the [features] as the order weighs it. */
    fun score(features: Map<String, Double>): Double =
        this.features.entries.sumOf { (name, f) -> f.weight * ((features[name] ?: 0.0) - f.mean) / f.scale }

    /**
     * What was [found], in the order the weights make — of works weighed alike, the order found —, the first of every
     * way of finding first of all ([leaders]).
     */
    fun ordered(found: SearchResult): SearchResult {
        val byScore = found.keys.sortedByDescending { score(found.features[it].orEmpty()) }
        val leaders = leaders(found).toSet()
        return SearchResult(
            found.line,
            byScore.filter { it in leaders } + byScore.filter { it !in leaders },
            found.state,
            found.nearest,
            found.prints,
            found.features,
        )
    }

    /**
     * THE FIRST OF EVERY WAY OF FINDING (user, 2026-11-14): of every feature any work found has — its closeness by
     * meaning, its words, the line in its name, the reranker's score — the work of the most of it. Whatever the
     * weights, every way the search finds by shows its best at the top: the works of the line's own word in their
     * titles are seen however much the meaning weighs.
     */
    private fun leaders(found: SearchResult): List<ItemKey> =
        found.keys
            .flatMap { found.features[it].orEmpty().keys }
            .distinct()
            .mapNotNull { feature ->
                found.keys
                    .filter { feature in found.features[it].orEmpty() }
                    .maxByOrNull { found.features.getValue(it).getValue(feature) }
            }
            .distinct()

    companion object {
        /**
         * The weights learnt of the [lines] judged; null when no line has a pair — two works judged to answer it
         * unlike.
         */
        fun fit(lines: List<JudgedLine>): SearchWeights? {
            val names = lines.flatMap { l -> l.works.flatMap { it.first.keys } }.toSortedSet().toList()
            val values = lines.flatMap { l ->
                l.works.map { w -> DoubleArray(names.size) { w.first[names[it]] ?: 0.0 } }
            }
            // Of no work judged, no pair below: nothing learnt
            val count = values.size.coerceAtLeast(1)
            val mean = DoubleArray(names.size) { j -> values.sumOf { it[j] } / count }
            val scale =
                DoubleArray(names.size) { j ->
                    sqrt(values.sumOf { (it[j] - mean[j]).let { d -> d * d } } / count).takeIf { it > 0 } ?: 1.0
                }
            fun scaled(f: Map<String, Double>) =
                DoubleArray(names.size) { j -> ((f[names[j]] ?: 0.0) - mean[j]) / scale[j] }
            val pairs = pairsOf(lines, ::scaled)
            return if (pairs.isEmpty()) null
            else
                descend(pairs, names.size).let { w ->
                    SearchWeights(names.indices.associate { names[it] to FeatureWeight(w[it], mean[it], scale[it]) })
                }
        }

        /**
         * Of every line, every work judged to answer it further less every work judged to answer it less, on the
         * features' own scales, each pair weighing 1 / the pairs of its line.
         */
        private fun pairsOf(lines: List<JudgedLine>, scaled: (Map<String, Double>) -> DoubleArray): List<Above> =
            lines.flatMap { line ->
                val works = line.works.map { scaled(it.first) to it.second.level }
                val ordered = works.flatMap { (x, lx) -> works.filter { (_, ly) -> lx > ly }.map { (y, _) -> x to y } }
                val weight = 1.0 / ordered.size.coerceAtLeast(1)
                ordered.map { (x, y) -> Above(DoubleArray(x.size) { x[it] - y[it] }, weight) }
            }

        /**
         * The weights of the logistic model of the [pairs], the first of each above the second, by gradient descent:
         * the mean loss over the lines with [L2] of the weights. The weights are w = 1/2 + v, v ≥ 0 summing to n/2 — of
         * n features, each at least half its equal share, all of them summing to n —: every step of v put back on that
         * simplex ([onSimplex]).
         */
        private fun descend(pairs: List<Above>, size: Int): DoubleArray {
            val free = size * FREE_SHARE
            var v = DoubleArray(size) { FREE_SHARE }
            fun weights() = DoubleArray(size) { 1 - FREE_SHARE + v[it] }
            val total = pairs.sumOf { it.weight }
            repeat(STEPS) {
                val w = weights()
                val grad = DoubleArray(size) { L2 * w[it] }
                pairs.forEach { p ->
                    var dot = 0.0
                    for (j in 0 until size) dot += w[j] * p.difference[j]
                    // d/dw log(1 + e^(−w·d)) = −σ(−w·d) · d
                    val g = -p.weight / total / (1 + exp(dot))
                    for (j in 0 until size) grad[j] += g * p.difference[j]
                }
                v = onSimplex(DoubleArray(size) { v[it] - RATE * grad[it] }, free)
            }
            return weights()
        }

        /**
         * The point nearest to [y] of every v ≥ 0 summing to [sum] (Duchi, Shalev-Shwartz, Singer, Chandra, 2008): y
         * less the one θ that leaves the sum, below nought put at it.
         */
        private fun onSimplex(y: DoubleArray, sum: Double): DoubleArray {
            val sorted = y.sortedDescending()
            var running = 0.0
            var theta = 0.0
            sorted.forEachIndexed { k, x ->
                running += x
                val t = (running - sum) / (k + 1)
                if (x - t > 0) theta = t
            }
            return DoubleArray(y.size) { (y[it] - theta).coerceAtLeast(0.0) }
        }

        /**
         * Two works of a line, one above the other: the features of the one that answers it less the other's, and its
         * weight.
         */
        private class Above(val difference: DoubleArray, val weight: Double)

        /** The steps of the descent: the loss of a few features is flat long before. */
        private const val STEPS = 2000

        /** How far a step goes along the gradient. */
        private const val RATE = 0.5

        /** The pull of the weights toward alike: a few lines judged tell no feature's weight sure. */
        private const val L2 = 0.05

        /** The share of a feature's weight the judgments may move: half; the other half every feature keeps. */
        private const val FREE_SHARE = 0.5
    }
}
