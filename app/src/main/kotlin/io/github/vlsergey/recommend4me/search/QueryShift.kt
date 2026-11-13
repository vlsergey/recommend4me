package io.github.vlsergey.recommend4me.search

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A line judged, as its meaning is searched: the vector of the line, and of every snippet judged for it
 * ([SearchSnippet]) its vector, of unit length, and how far it answers the line.
 */
class SnippetLine(val query: FloatArray, val snippets: List<Pair<FloatArray, SearchFit>>)

/**
 * THE CLOSENESS OF A LINE AND A PASSAGE BY MEANING, CORRECTED BY THE USER'S JUDGMENTS (user, 2026-11-13): of the
 * encoder's own, q·p, made qᵀ(I + a·bᵀ)p = q·p + (q·a)(b·p) — the matrix of the corrections the outer product of two
 * vectors of weights of the dimensions, [a] of the line's side and [b] of the passage's. The same as the line's vector
 * moved ([of]): q' = q + b(a·q), searched among the passages as they are, nothing of the index made again.
 *
 * Learnt ([fit]) of the snippets judged, by pairs within a line (user, 2026-11-13: the word is on the snippet, not the
 * work): every snippet judged to answer it further than another ([SearchFit]) the closer to it; every line alike; [a]
 * and [b] pulled toward none, the encoder's closeness.
 */
class QueryShift(val a: FloatArray, val b: FloatArray) {

    /** The line's vector [q] moved so that its closeness to a passage is the corrected one. */
    fun of(q: FloatArray): FloatArray {
        if (q.size != a.size) return q
        val s = dot(a, q)
        return FloatArray(q.size) { (q[it] + b[it] * s).toFloat() }
    }

    companion object {
        /**
         * The correction learnt of the [lines] judged, of vectors of one dimension; null when no line has a snippet
         * judged to answer it further than another.
         */
        fun fit(lines: List<SnippetLine>): QueryShift? {
            val dim = lines.firstOrNull()?.query?.size ?: return null
            val usable =
                lines
                    .filter { it.query.size == dim }
                    .map { l -> SnippetLine(l.query, l.snippets.filter { (p, _) -> p.size == dim }) }
                    .filter { l -> l.snippets.map { it.second }.distinct().size > 1 }
            return if (usable.isEmpty()) null else Descent(usable, dim).run()
        }

        private fun dot(x: FloatArray, y: FloatArray): Double {
            var s = 0.0
            for (i in x.indices) s += x[i].toDouble() * y[i]
            return s
        }

        /**
         * The descent of the loss of the [lines] from a small start — at none the product moves neither vector — along
         * the mean direction of the lines and of the snippets that answer them less those that do not.
         */
        private class Descent(val lines: List<SnippetLine>, val dim: Int) {
            val a = DoubleArray(dim)
            val b = DoubleArray(dim)

            fun run(): QueryShift {
                start()
                repeat(STEPS) { step() }
                return QueryShift(FloatArray(dim) { a[it].toFloat() }, FloatArray(dim) { b[it].toFloat() })
            }

            private fun start() {
                lines.forEach { l ->
                    for (i in 0 until dim) a[i] += l.query[i].toDouble()
                    // From the snippets that answer it furthest away from those that answer it least
                    val top = l.snippets.maxOf { it.second.level }
                    val bottom = l.snippets.minOf { it.second.level }
                    val yes = l.snippets.filter { it.second.level == top }
                    val no = l.snippets.filter { it.second.level == bottom }
                    yes.forEach { (p, _) -> for (i in 0 until dim) b[i] += p[i].toDouble() / yes.size }
                    no.forEach { (p, _) -> for (i in 0 until dim) b[i] -= p[i].toDouble() / no.size }
                }
                scaleTo(a, START)
                scaleTo(b, START)
            }

            private fun scaleTo(v: DoubleArray, length: Double) {
                val n = sqrt(v.sumOf { it * it })
                if (n > 0) for (i in v.indices) v[i] *= length / n
            }

            /** One step: the gradient of the mean loss of the lines and of the pull toward none. */
            private fun step() {
                val ga = DoubleArray(dim) { L2 * a[it] }
                val gb = DoubleArray(dim) { L2 * b[it] }
                lines.forEach { l -> lineGradient(l, ga, gb) }
                for (i in 0 until dim) {
                    a[i] -= RATE * ga[i]
                    b[i] -= RATE * gb[i]
                }
            }

            /**
             * The gradient of the line [l]'s loss, 1 / the lines of it, added to [ga] and [gb]: through the line's
             * vector moved, q' = q + b(a·q), of every pair of its snippets judged apart the logistic loss of their
             * closeness q'·p told apart.
             */
            private fun lineGradient(l: SnippetLine, ga: DoubleArray, gb: DoubleArray) {
                val q = l.query
                var aq = 0.0
                for (i in 0 until dim) aq += a[i] * q[i]
                val moved = DoubleArray(dim) { q[it] + b[it] * aq }
                val scored =
                    l.snippets.map { (p, fit) ->
                        var x = 0.0
                        for (i in 0 until dim) x += moved[i] * p[i]
                        Scored(x, p, fit.level)
                    }
                val ordered = scored.flatMap { y -> scored.filter { n -> y.level > n.level }.map { n -> y to n } }
                val weight = 1.0 / ordered.size / lines.size
                // The gradient of the loss by the line's vector moved
                val g = DoubleArray(dim)
                ordered.forEach { (y, n) ->
                    val margin = (y.score - n.score) / TEMPERATURE
                    val d = -weight / TEMPERATURE / (1 + exp(margin))
                    for (i in 0 until dim) g[i] += d * (y.vector[i] - n.vector[i])
                }
                var bg = 0.0
                for (i in 0 until dim) bg += b[i] * g[i]
                for (i in 0 until dim) {
                    ga[i] += bg * q[i]
                    gb[i] += aq * g[i]
                }
            }
        }

        /** A snippet's closeness to the line's vector moved, its vector and how far it answers the line. */
        private class Scored(val score: Double, val vector: FloatArray, val level: Int)

        /** How sharp the closeness of two snippets is told apart. */
        private const val TEMPERATURE = 0.05

        /** The length of each of the two vectors the descent starts from: a correction of a hundredth or so. */
        private const val START = 0.1

        private const val STEPS = 300

        private const val RATE = 0.02

        /**
         * The pull of the two vectors toward none, the encoder's own closeness: a few lines judged tell little sure.
         */
        private const val L2 = 1.0
    }
}
