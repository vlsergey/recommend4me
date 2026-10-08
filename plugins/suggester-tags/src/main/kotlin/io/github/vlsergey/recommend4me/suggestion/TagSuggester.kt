package io.github.vlsergey.recommend4me.suggestion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * VALUES FROM WITNESSES, weighed by what the user's catalogue says of them ([Witnesses]):
 *
 * - the NEIGHBOURS — in every view of the texts (the description, the chapters, the site's own line
 *   of the values), the works alike: the share of them that has the value;
 * - the NAME — how much nearer the view is to the value's name than usually;
 * - the OTHER VALUES of the facet, and the values of the other facets (the fandom, the tags) — how
 *   much more often the value goes with them than by chance.
 *
 * A logistic regression over these few numbers learns how much each witness is worth from every
 * work and every value: the values a work has are its positives, all the others its negatives — of
 * the works that have any value or a word of the user: an untagged work is unknown, not denied. The
 * user's word is an example like any other: what it decides of a work — it has the value, or it has
 * it not — the application applies over the chance. The weights are all that is learnt: the
 * witnesses are asked anew of the catalogue at every scoring.
 *
 * A WORK IS JUDGED BY THE VIEWS IT HAS. Works differ in the views of their texts — one has its
 * chapters read, another has no line of the values on the site — and in the values of their other
 * facets — one the site tagged, another not — and weights learnt where a view is always there say
 * nothing of a work without it. So the weights are learnt for every set of
 * views the works have, each from every work as if it had only that set (and for none, the values
 * alone); a work is judged by the weights of its own set.
 */
class TagSuggester : FacetSuggester {
    /** With the version of the witnesses: weights fitted on other witnesses are fitted again. */
    override val id = "tag-witnesses-6"

    override fun fit(task: SuggestionTask): FittedSuggester? {
        val positives = task.assigned.sumOf { it.distinct().size }.toLong()
        if (positives == 0L || positives == task.itemCount.toLong() * task.valueCount) return null
        val witnesses = Witnesses(task)
        val sets = witnesses.viewSets() + 0
        // The examples worked out once when they fit in memory, worked out anew at every step when not
        val held = if (witnesses.exampleCount() * witnesses.features <= HELD_FLOATS) witnesses.hold() else null
        return Fitted(sets.associateWith { set ->
            Logistic.fit(witnesses.features) { visit -> if (held != null) held.forEach(set, visit) else witnesses.forEachExample(set, visit) }
        })
    }

    companion object {
        /** The floats of examples a fitting holds at most: memory, not meaning. */
        private const val HELD_FLOATS = 64L * 1024 * 1024
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val features = buffer.int
        // Weights of another number of views or groupings are of another regression
        if (features != Witnesses.featuresOf(task.views.size, task.groupings.size)) return null
        val sets = buffer.int
        return Fitted((0 until sets).associate { buffer.int to DoubleArray(features) { buffer.double } })
    }

    /** The weights of every set of views, a bit per view of the task. */
    class Fitted(val weights: Map<Int, DoubleArray>) : FittedSuggester {
        override fun on(task: SuggestionTask): SuggestionScores {
            val witnesses = Witnesses(task)
            check(weights.values.all { witnesses.features == it.size }) { "weights for other features than ${witnesses.features}" }
            return SuggestionScores { rows ->
                witnesses.blocks(rows).flatMap { block ->
                    witnesses.features(block).mapIndexed { b, values ->
                        // A set of views no work had when fitted: the largest fitted set within it
                        val own = witnesses.viewsOf(block[b])
                        val set = weights.keys.filter { it and own == it }.maxBy { Integer.bitCount(it) }
                        val w = weights.getValue(set)
                        witnesses.keepViews(values, set)
                        FloatArray(values.size) { v -> Logistic.chance(w, values[v]).toFloat() }
                    }
                }
            }
        }

        override fun pack(): ByteArray {
            val features = weights.values.first().size
            val buffer = ByteBuffer.allocate(8 + weights.size * (4 + 8 * features)).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(features).putInt(weights.size)
            weights.forEach { (set, w) ->
                buffer.putInt(set)
                w.forEach { buffer.putDouble(it) }
            }
            return buffer.array()
        }
    }
}

/**
 * A logistic regression of a few features over examples too many to hold, fitted by Newton's
 * method: every step reads the examples anew, as the caller hands them over.
 */
internal object Logistic {

    fun chance(w: DoubleArray, x: FloatArray): Double {
        var z = 0.0
        for (k in w.indices) z += w[k] * x[k]
        return 1.0 / (1.0 + exp(-z))
    }

    /**
     * [examples] calls its argument with every example: its features, whether it is positive and
     * how much it weighs — its share in the likelihood, as if it were that many examples.
     *
     * THE WEIGHTS OF FIRTH'S PENALISED LIKELIHOOD — the likelihood times the Jeffreys prior,
     * det(I)^½ of the Fisher information: they stay finite when a feature tells the examples
     * apart outright, where the plain maximum runs off to infinity, and nothing is chosen by hand.
     * A feature that is zero in every example, or repeats others, has no information of its own
     * and keeps its weight of zero.
     */
    fun fit(features: Int, examples: ((FloatArray, Boolean, Double) -> Unit) -> Unit): DoubleArray {
        var w = DoubleArray(features)
        var at = information(features, w, examples)
        var step = DoubleArray(features)
        var scale = 1.0
        var proposed = w
        // A step that does not lower the penalised loss of this problem is halved until it does;
        // when halving no longer moves the weights in a double, the optimum is reached
        while (true) {
            if (proposed !== w) {
                val there = information(features, proposed, examples)
                if (there.loss < at.loss) {
                    w = proposed
                    at = there
                    scale = 1.0
                } else {
                    scale /= 2
                    val shorter = DoubleArray(features) { w[it] + scale * step[it] }
                    if (shorter.contentEquals(w)) return w
                    proposed = shorter
                    continue
                }
            }
            step = firthStep(features, w, at.inverse, examples)
            val next = DoubleArray(features) { w[it] + step[it] }
            if (next.contentEquals(w)) return w
            proposed = next
        }
    }

    /** The penalised loss at the weights [w], and the inverse of the Fisher information there. */
    private class Information(val loss: Double, val inverse: Array<DoubleArray>)

    private fun information(features: Int, w: DoubleArray, examples: ((FloatArray, Boolean, Double) -> Unit) -> Unit): Information {
        val h = Array(features) { DoubleArray(features) }
        var loss = 0.0
        examples { x, positive, weight ->
            var z = 0.0
            for (k in 0 until features) z += w[k] * x[k]
            val p = 1.0 / (1.0 + exp(-z))
            // -log of the chance of what the example is, without overflow either way
            val margin = if (positive) z else -z
            loss += weight * (if (margin > 0) ln1p(exp(-margin)) else -margin + ln1p(exp(margin)))
            val c = weight * p * (1 - p)
            for (a in 0 until features) {
                if (x[a] == 0f) continue
                for (b in 0..a) h[a][b] += c * x[a] * x[b]
            }
        }
        for (a in 0 until features) for (b in 0 until a) h[b][a] = h[a][b]
        val (inverse, logDet) = invert(h)
        return Information(loss - logDet / 2, inverse)
    }

    /** Firth's step from [w]: the information's inverse times the score with every example's leverage h given half to each side. */
    private fun firthStep(features: Int, w: DoubleArray, inverse: Array<DoubleArray>, examples: ((FloatArray, Boolean, Double) -> Unit) -> Unit): DoubleArray {
        val score = DoubleArray(features)
        examples { x, positive, weight ->
            var z = 0.0
            for (k in 0 until features) z += w[k] * x[k]
            val p = 1.0 / (1.0 + exp(-z))
            var quadratic = 0.0
            for (a in 0 until features) {
                if (x[a] == 0f) continue
                var row = 0.0
                for (b in 0 until features) row += inverse[a][b] * x[b]
                quadratic += x[a] * row
            }
            val leverage = weight * p * (1 - p) * quadratic
            val e = weight * ((if (positive) 1.0 else 0.0) - p) + leverage * (0.5 - p)
            for (a in 0 until features) score[a] += e * x[a]
        }
        return DoubleArray(features) { a -> (0 until features).sumOf { b -> inverse[a][b] * score[b] } }
    }

    /**
     * The inverse of the symmetric [h] and the log of its determinant, over the directions it has
     * curvature in. A feature that repeats others — "has a description" when every work has one,
     * the same as the bias — adds no direction: it is left out, its weight stays zero. Which
     * features are independent is told by the numerical rank: a pivot within the rounding error of
     * the elimination ([size] ulps of the largest diagonal) is no pivot.
     */
    private fun invert(h: Array<DoubleArray>): Pair<Array<DoubleArray>, Double> {
        val size = h.size
        val tolerance = size * Math.ulp((0 until size).maxOfOrNull { h[it][it] } ?: 0.0)
        // Cholesky with diagonal pivoting over the independent directions
        val independent = ArrayList<Int>()
        for (k in 0 until size) {
            val candidate = independent + k
            if (positiveDefinite(h, candidate, tolerance)) independent += k
        }
        val m = independent.size
        val a = Array(m) { i -> DoubleArray(2 * m) { j -> if (j < m) h[independent[i]][independent[j]] else if (j - m == i) 1.0 else 0.0 } }
        var logDet = 0.0
        for (col in 0 until m) {
            val pivot = (col until m).maxBy { abs(a[it][col]) }
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val p = a[col][col]
            logDet += ln(abs(p))
            for (c in 0 until 2 * m) a[col][c] /= p
            for (r in 0 until m) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in 0 until 2 * m) a[r][c] -= f * a[col][c]
            }
        }
        val inverse = Array(size) { DoubleArray(size) }
        for (i in 0 until m) for (j in 0 until m) inverse[independent[i]][independent[j]] = a[i][m + j]
        return inverse to logDet
    }

    /** Whether [h] restricted to the rows and columns [rows] is positive definite beyond [tolerance]: its Cholesky factor has no pivot within it. */
    private fun positiveDefinite(h: Array<DoubleArray>, rows: List<Int>, tolerance: Double): Boolean {
        val n = rows.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = h[rows[i]][rows[j]]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= tolerance) return false
                    l[i][i] = kotlin.math.sqrt(s)
                } else l[i][j] = s / l[j][j]
            }
        }
        return true
    }
}
