package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.CONSTANT
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.DESCRIPTION
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.SAME_AUTHOR
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.SIZE
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.TITLE
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.facet
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.has
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.sigmoid
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.sites
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.min

/**
 * THE PAIRS AS NEWTON'S STEPS OF THE LOGISTIC MODEL READ THEM: of every pair the places of the features it has (by
 * [names]: the constant, every layer both have and that they have it, its two sites) and their values, laid out in flat
 * arrays — the i-th pair's from [start] (i) to [start] (i + 1) — made once of the layers, no map made of a pair, and
 * the steps of the weights taken on them ([step]).
 */
internal class PairRows
private constructor(
    val names: List<String>,
    private val start: IntArray,
    private val places: IntArray,
    private val values: FloatArray,
) {
    private val size: Int
        get() = start.size - 1

    /**
     * One Newton's step of the weights [w] by the user's word on each pair ([said]: [YES], [NO], [UNSAID]), a pair not
     * answered weighing [lack]: how far the weights moved, the most of any. The pairs summed in chunks at once, the
     * chunks' sums added after: another order of the sums, the last digits alone.
     */
    fun step(w: DoubleArray, said: ByteArray, lack: Double): Double {
        val count = w.size
        val chunks = (size + CHUNK - 1) / CHUNK
        val sums = DoubleArray(count + count * count)
        IntStream.range(0, chunks)
            .parallel()
            .mapToObj { c -> slopes(w, said, lack, c * CHUNK, min(size, (c + 1) * CHUNK)) }
            .toList()
            .forEach { part -> for (k in sums.indices) sums[k] += part[k] }
        val gradient = sums.copyOf(count)
        // The products of two features summed once a pair of them, the other half of the matrix its mirror
        val hessian = Array(count) { a -> DoubleArray(count) { b -> upper(sums, count, a, b) } }
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

    /** The score of the [i]-th pair by the weights [w]: the logit of its chance. */
    fun logit(w: DoubleArray, i: Int): Double {
        var z = 0.0
        for (k in start[i] until start[i + 1]) z += w[places[k]] * values[k]
        return z
    }

    /**
     * Of the pairs [from] to [until], the slope of the loss by every weight, then the products of their features (of a
     * pair of features once: the place of the first's row, the second's column, whichever comes first in the pair).
     */
    private fun slopes(w: DoubleArray, said: ByteArray, lack: Double, from: Int, until: Int): DoubleArray {
        val count = w.size
        val out = DoubleArray(count + count * count)
        for (i in from until until) {
            if (said[i] == HELD) continue
            val weight = if (said[i] == UNSAID) lack else 1.0
            val y = if (said[i] == YES) 1.0 else 0.0
            val a = start[i]
            val b = start[i + 1]
            val chance = sigmoid(logit(w, i))
            val r = weight * chance * (1 - chance)
            val g = weight * (chance - y)
            for (k in a until b) {
                val pk = places[k]
                val xk = values[k].toDouble()
                out[pk] += g * xk
                val row = count + pk * count
                val rx = r * xk
                for (q in k until b) out[row + places[q]] += rx * values[q]
            }
        }
        return out
    }

    companion object {
        /** The user's word on a pair: of the kind, not of it, not said; or a word left out of the fitting. */
        const val YES: Byte = 1
        const val NO: Byte = 0
        const val UNSAID: Byte = -1
        const val HELD: Byte = 2

        /** The pairs a thread sums at a time: work enough a task, not meaning. */
        private const val CHUNK = 16_384

        /** How far the weights are held to nought: the user's few answers do not swing them wide. */
        private const val PENALTY = 1.0

        /** A pivot nearer nought than this leaves its weight where it is. */
        private const val SINGULAR = 1e-12

        /** The features before the facets', by their places. */
        private val HEAD = listOf(CONSTANT, TITLE, DESCRIPTION, has(DESCRIPTION), SIZE, has(SIZE), SAME_AUTHOR)

        /**
         * The features of the [pairs]: every one any pair has, the facets by their names in order, then the sites by
         * theirs.
         */
        fun of(pairs: List<PairLayers>): PairRows {
            val families = pairs.flatMapTo(HashSet()) { it.facets.keys }.sorted()
            val familyAt = families.withIndex().associate { it.value to it.index }
            val siteNames = pairs.mapTo(HashSet()) { sites(it.site, it.otherSite) }.sorted()
            val siteAt = siteNames.withIndex().associate { it.value to HEAD.size + 2 * families.size + it.index }
            val names = HEAD + families.flatMap { listOf(facet(it), has(facet(it))) } + siteNames
            val start = IntArray(pairs.size + 1)
            pairs.forEachIndexed { i, c ->
                // The constant, the title, the sites
                var n = 3 + 2 * c.facets.size
                if (c.description != null) n += 2
                if (c.size != null) n += 2
                if (c.sameAuthor) n++
                start[i + 1] = start[i] + n
            }
            val places = IntArray(start[pairs.size])
            val values = FloatArray(start[pairs.size])
            pairs.forEachIndexed { i, c ->
                var at = start[i]
                fun put(place: Int, x: Float) {
                    places[at] = place
                    values[at] = x
                    at++
                }
                // A layer's value, then that both have it, side by side
                put(HEAD.indexOf(CONSTANT), 1f)
                put(HEAD.indexOf(TITLE), c.title)
                c.description?.let {
                    put(HEAD.indexOf(DESCRIPTION), it)
                    put(HEAD.indexOf(DESCRIPTION) + 1, 1f)
                }
                c.size?.let {
                    put(HEAD.indexOf(SIZE), it)
                    put(HEAD.indexOf(SIZE) + 1, 1f)
                }
                if (c.sameAuthor) put(HEAD.indexOf(SAME_AUTHOR), 1f)
                c.facets.forEach { (family, v) ->
                    val k = HEAD.size + 2 * familyAt.getValue(family)
                    put(k, v)
                    put(k + 1, 1f)
                }
                put(siteAt.getValue(sites(c.site, c.otherSite)), 1f)
            }
            return PairRows(names, start, places, values)
        }

        /** Of the [sums] of [slopes], the product of the features [a] and [b] summed whichever came first. */
        private fun upper(sums: DoubleArray, count: Int, a: Int, b: Int): Double {
            val ab = sums[count + a * count + b]
            return if (a == b) ab else ab + sums[count + b * count + a]
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
                if (abs(m[c][c]) < SINGULAR) continue
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = m[r][c] / m[c][c]
                    if (f != 0.0) for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { if (abs(m[it][it]) < SINGULAR) 0.0 else m[it][n] / m[it][it] }
        }
    }
}
