package io.github.vlsergey.recommend4me.search

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A line judged, as its meaning is searched: the vector of the line, and of every work judged the vectors of its
 * passages, of unit length, and whether it answers the line.
 */
class MeaningLine(val query: FloatArray, val works: List<Pair<List<FloatArray>, Boolean>>)

/**
 * THE CLOSENESS OF A LINE AND A PASSAGE BY MEANING, CORRECTED BY THE USER'S JUDGMENTS (user, 2026-11-13): of the
 * encoder's own, q·p, made qᵀ(I + a·bᵀ)p = q·p + (q·a)(b·p) — the matrix of the corrections the outer product of two
 * vectors of weights of the dimensions, [a] of the line's side and [b] of the passage's. The same as the line's vector
 * moved ([of]): q' = q + b(a·q), searched among the passages as they are, nothing of the index made again.
 *
 * Learnt ([fit]) by pairs within a line, as the order of what is found ([SearchWeights]): every work judged to answer
 * it against every work judged not to, a work as close as its passages are — the soft maximum of their closeness — the
 * first to be the closer; every line alike; [a] and [b] pulled toward none, the encoder's closeness.
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
         * The correction learnt of the [lines] judged, of vectors of one dimension; null when no line has a work judged
         * to answer it and one judged not to, each with a passage.
         */
        fun fit(lines: List<MeaningLine>): QueryShift? {
            val dim = lines.firstOrNull()?.query?.size ?: return null
            val usable =
                lines
                    .filter { it.query.size == dim }
                    .map { l -> Prepared(l.query, l.works.filter { (ps, _) -> ps.isNotEmpty() && ps[0].size == dim }) }
                    .filter { l -> l.works.any { it.second } && l.works.any { !it.second } }
            return if (usable.isEmpty()) null else Descent(usable, dim).run()
        }

        private fun dot(x: FloatArray, y: FloatArray): Double {
            var s = 0.0
            for (i in x.indices) s += x[i].toDouble() * y[i]
            return s
        }

        /** A line ready for the descent: its vector, and its works' passages with whether each answers it. */
        private class Prepared(val query: FloatArray, val works: List<Pair<List<FloatArray>, Boolean>>)

        /**
         * The descent of the loss of the [lines] from a small start — at none the product moves neither vector — along
         * the mean direction of the lines and of the passages of the works that answer them less those that do not.
         */
        private class Descent(val lines: List<Prepared>, val dim: Int) {
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
                    val (yes, no) = l.works.partition { it.second }
                    yes.forEach { (ps, _) ->
                        ps.forEach { p -> for (i in 0 until dim) b[i] += p[i].toDouble() / ps.size / yes.size }
                    }
                    no.forEach { (ps, _) ->
                        ps.forEach { p -> for (i in 0 until dim) b[i] -= p[i].toDouble() / ps.size / no.size }
                    }
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
             * vector moved, q' = q + b(a·q), of every work its closeness τ·log Σ exp(q'·p / τ) and its direction, the
             * passages' mean weighed by their softmax.
             */
            private fun lineGradient(l: Prepared, ga: DoubleArray, gb: DoubleArray) {
                val q = l.query
                var aq = 0.0
                for (i in 0 until dim) aq += a[i] * q[i]
                val moved = DoubleArray(dim) { q[it] + b[it] * aq }
                val scored = l.works.map { (ps, fits) -> soft(moved, ps) to fits }
                val yes = scored.filter { it.second }
                val no = scored.filter { !it.second }
                val weight = 1.0 / (yes.size * no.size) / lines.size
                // The gradient of the loss by the line's vector moved
                val g = DoubleArray(dim)
                yes.forEach { (y, _) ->
                    no.forEach { (n, _) ->
                        val margin = (y.score - n.score) / TEMPERATURE
                        val d = -weight / TEMPERATURE / (1 + exp(margin))
                        for (i in 0 until dim) g[i] += d * (y.direction[i] - n.direction[i])
                    }
                }
                var bg = 0.0
                for (i in 0 until dim) bg += b[i] * g[i]
                for (i in 0 until dim) {
                    ga[i] += bg * q[i]
                    gb[i] += aq * g[i]
                }
            }

            /**
             * A work's closeness to the line's vector [moved] by its passages [ps], softly their best, and its
             * direction.
             */
            private fun soft(moved: DoubleArray, ps: List<FloatArray>): Soft {
                val s = ps.map { p ->
                    var x = 0.0
                    for (i in 0 until dim) x += moved[i] * p[i]
                    x / TEMPERATURE
                }
                val top = s.max()
                val e = s.map { exp(it - top) }
                val total = e.sum()
                val direction = DoubleArray(dim)
                ps.forEachIndexed { k, p -> for (i in 0 until dim) direction[i] += e[k] / total * p[i] }
                return Soft(TEMPERATURE * (top + ln(total)), direction)
            }
        }

        private class Soft(val score: Double, val direction: DoubleArray)

        /** How sharp a work's best passage is told from its others, and the closeness of two works apart. */
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
