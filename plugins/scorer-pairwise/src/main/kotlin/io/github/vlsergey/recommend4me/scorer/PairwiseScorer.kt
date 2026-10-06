package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * PAIRWISE LOGISTIC RANKING — RankNet with a line. From every pair of rated works of different
 * grades it learns a score that puts the better one higher: the grades carry no numbers, only
 * which of two is better. A grade is not a fixed point either: "do not like it" holds works bad in
 * every way and works of the wrong story alike, and they may lie far apart on the line.
 *
 * The parameter is C of the L2 penalty: the loss is the mean over pairs plus ||w||² / (2 C · pairs),
 * so C means what it means for a model of single rows.
 */
class PairwiseScorer : Scorer {
    override val id = "pairwise-logistic"
    override val title = "Попарное логистическое ранжирование"
    override val candidates = listOf(0.01, 0.03, 0.1, 0.3, 1.0)
    override val defaultParameter = 0.1

    override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer =
        fit(task.x, pairs(task.grades, task.works, rows), parameter)

    override fun unpack(bytes: ByteArray): FittedScorer = LinearModel.unpack(bytes)

    /**
     * Rated rows i (better) and j (worse): different grades, different works; every pair of works
     * weighs the same. Not every pair of grades: the works graded "do not like it" are most of the
     * rated, and telling them from the rest is most of what the model is for — weighed as one pair
     * of grades of ten, a pair of grades of four works weighed as much.
     */
    class Pairs(val better: IntArray, val worse: IntArray) {
        val size: Int get() = better.size
    }

    companion object {
        fun pairs(grades: IntArray, works: LongArray, subset: IntArray): Pairs {
            val better = ArrayList<Int>()
            val worse = ArrayList<Int>()
            for (a in subset) for (b in subset) {
                if (grades[a] > grades[b] && works[a] != works[b]) {
                    better += a
                    worse += b
                }
            }
            return Pairs(better.toIntArray(), worse.toIntArray())
        }

        /** Fits the line on [pairs] of the rows of [x]: the logistic loss of score(better) − score(worse), L2 with [c]. */
        fun fit(x: Matrix, pairs: Pairs, c: Double, maxIter: Int = 300, tol: Double = 1e-4): LinearModel {
            val objective = PairObjective(x, pairs, c)
            val solver = Lbfgs(x.cols, maxIter, tol)
            val g = DoubleArray(x.cols + 1)
            while (!solver.done) solver.tell(objective.at(solver.at, g), g)
            return LinearModel(FloatArray(x.cols) { solver.theta[it].toFloat() }, 0f)
        }
    }

    /**
     * The loss over pairs without a matrix of pairs: the scores of the rows are one product
     * (z = X·w), and the gradient one more (Xᵀ·q) once every pair's residual is added to its
     * better row and taken from its worse one.
     */
    private class PairObjective(val x: Matrix, val pairs: Pairs, val c: Double) {
        private val n = x.rows
        private val d = x.cols
        private val count = max(pairs.size, 1).toDouble()
        private val w = Floats(d)
        private val z = Floats(n)
        private val q = Floats(n)
        private val gw = Floats(d)

        fun at(th: DoubleArray, g: DoubleArray): Double {
            for (k in 0 until d) w[k] = th[k].toFloat()
            // One part: the cross-validation already fills the cores with fits of its own
            Blas.times(x.held, d, n, d, w, z, parts = 1)
            q.clear()
            var loss = 0.0
            for (p in 0 until pairs.size) {
                val s = (z[pairs.better[p]] - z[pairs.worse[p]]).toDouble()
                // log(1 + exp(−s)) without overflow
                loss += if (s < 0) -s + ln(1.0 + exp(s)) else ln(1.0 + exp(-s))
                val r = 1.0 / (1.0 + exp(-s)) - 1.0
                q[pairs.better[p]] = q[pairs.better[p]] + r.toFloat()
                q[pairs.worse[p]] = q[pairs.worse[p]] - r.toFloat()
            }
            Blas.transposedTimes(x.held, d, n, d, q, gw)
            var pen = 0.0
            for (k in 0 until d) {
                pen += th[k] * th[k]
                g[k] = (gw[k] + th[k] / c) / count
            }
            g[d] = 0.0
            return (loss + 0.5 * pen / c) / count
        }
    }
}

/** A linear model: `score = w · x + b`. */
class LinearModel(val w: FloatArray, val b: Float) : FittedScorer {

    private val wv = Floats.of(w)

    override fun scores(x: Matrix, rows: Int): FloatArray {
        check(x.cols == w.size) { "The line has ${w.size} weights for rows of ${x.cols}" }
        val out = Floats(rows)
        Blas.times(x.held, x.cols, rows, x.cols, wv, out)
        return FloatArray(rows) { out[it] + b }
    }

    /** float32 little-endian: w, then b. */
    override fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate((w.size + 1) * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(w).put(b)
        return buffer.array()
    }

    companion object {
        fun unpack(bytes: ByteArray): LinearModel {
            val all = FloatArray(bytes.size / Float.SIZE_BYTES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(all)
            return LinearModel(all.copyOfRange(0, all.size - 1), all.last())
        }
    }
}
