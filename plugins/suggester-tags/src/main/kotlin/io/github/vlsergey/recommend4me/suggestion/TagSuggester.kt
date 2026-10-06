package io.github.vlsergey.recommend4me.suggestion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.random.Random

/**
 * TAGS FROM THREE WITNESSES, weighed by what the user's catalogue says of them:
 *
 * - the NEIGHBOURS — the works whose texts mean the most alike: the share of them that has the tag;
 * - the NAME — how much nearer the text is to the tag's name than texts usually are;
 * - the OTHER TAGS of the work — how much more often the tag goes with them than by chance.
 *
 * A logistic regression over these few numbers learns how much each witness is worth from the
 * tags the works already have (positives) and the ones they lack (negatives, a sample weighed up to
 * all of them, so the chances come out as chances); a tag the user confirmed or rejected weighs
 * [USER_WEIGHT] times as much. The weights are all that is learnt: the witnesses are asked anew
 * of the catalogue as it is at every scoring.
 */
class TagSuggester : FacetSuggester {
    override val id = "tag-witnesses"

    override fun fit(task: SuggestionTask): FittedSuggester {
        val witnesses = Witnesses(task)
        val random = Random(SEED)
        val labelled = (0 until task.itemCount).filter { task.assigned[it].isNotEmpty() || task.rejected[it].isNotEmpty() }
        val byUser = labelled.filter { task.confirmed[it].isNotEmpty() || task.rejected[it].isNotEmpty() }.toSet()
        val rows = (byUser + labelled.filter { it !in byUser }.shuffled(random).take(TRAINING_ITEMS)).toIntArray()
        if (rows.isEmpty()) return Fitted(DEFAULT_WEIGHTS)

        val features = ArrayList<FloatArray>()
        val labels = ArrayList<Float>()
        val weights = ArrayList<Float>()
        for (block in rows.toList().chunked(BLOCK)) {
            val all = witnesses.features(block.toIntArray())
            block.forEachIndexed { b, i ->
                val has = task.assigned[i].toSet()
                val confirmed = task.confirmed[i].toSet()
                val rejected = task.rejected[i].toSet()
                has.forEach { v ->
                    features += all[b][v]
                    labels += 1f
                    weights += if (v in confirmed) USER_WEIGHT else 1f
                }
                rejected.forEach { v ->
                    features += all[b][v]
                    labels += 0f
                    weights += USER_WEIGHT
                }
                // The rest of the values: a sample standing for all of them
                val others = task.valueCount - has.size - rejected.size
                if (others <= 0) return@forEachIndexed
                val wanted = minOf(NEGATIVES, others)
                val picked = HashSet<Int>()
                var tries = 0
                while (picked.size < wanted && tries++ < wanted * 20) {
                    val v = random.nextInt(task.valueCount)
                    if (v !in has && v !in rejected) picked += v
                }
                picked.forEach { v ->
                    features += all[b][v]
                    labels += 0f
                    weights += others.toFloat() / picked.size
                }
            }
        }
        if (labels.count { it > 0.5f } < MIN_POSITIVES) return Fitted(DEFAULT_WEIGHTS)
        return Fitted(Logistic.fit(features, labels.toFloatArray(), weights.toFloatArray()))
    }

    override fun unpack(bytes: ByteArray): FittedSuggester {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buffer.int
        if (n != Witnesses.FEATURES) return Fitted(DEFAULT_WEIGHTS)
        return Fitted(DoubleArray(n) { buffer.double })
    }

    class Fitted(val weights: DoubleArray) : FittedSuggester {
        override fun on(task: SuggestionTask): SuggestionScores {
            val witnesses = Witnesses(task)
            return SuggestionScores { rows ->
                rows.toList().chunked(BLOCK).flatMap { block ->
                    witnesses.features(block.toIntArray()).map { values ->
                        FloatArray(values.size) { v -> Logistic.chance(weights, values[v]).toFloat() }
                    }
                }
            }
        }

        override fun pack(): ByteArray {
            val buffer = ByteBuffer.allocate(4 + 8 * weights.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(weights.size)
            weights.forEach { buffer.putDouble(it) }
            return buffer.array()
        }
    }

    companion object {
        private const val SEED = 20261006L

        /** Items the weights are learnt from, beyond those the user corrected: a few numbers need no more. */
        private const val TRAINING_ITEMS = 6_000

        /** Values an item lacks, sampled to stand for all of them. */
        private const val NEGATIVES = 24

        private const val USER_WEIGHT = 5f

        /** Rows scored at once: a block of cosines to the whole catalogue. */
        private const val BLOCK = 256

        private const val MIN_POSITIVES = 30

        /**
         * The weights before there is anything to learn from: the neighbours and the other tags
         * as they say, the name a little, the rarity of a tag against it.
         */
        val DEFAULT_WEIGHTS = doubleArrayOf(-1.0, 0.0, 1.0, 0.4, 0.0, 0.6, 0.2, 0.8)
    }
}

/** A logistic regression of a few features, fitted by Newton's method. */
internal object Logistic {

    fun chance(w: DoubleArray, x: FloatArray): Double {
        var z = 0.0
        for (k in w.indices) z += w[k] * x[k]
        return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }

    fun fit(x: List<FloatArray>, y: FloatArray, s: FloatArray, iterations: Int = 40, ridge: Double = 1e-4): DoubleArray {
        val d = x[0].size
        val w = DoubleArray(d)
        val total = s.sumOf { it.toDouble() }
        val lambda = ridge * total
        repeat(iterations) {
            val g = DoubleArray(d)
            val h = Array(d) { DoubleArray(d) }
            for (r in x.indices) {
                val p = chance(w, x[r])
                val e = s[r] * (p - y[r])
                val c = s[r] * p * (1 - p)
                val f = x[r]
                for (a in 0 until d) {
                    g[a] += e * f[a]
                    for (b in 0..a) h[a][b] += c * f[a] * f[b]
                }
            }
            for (a in 0 until d) {
                for (b in 0 until a) h[b][a] = h[a][b]
                // The bias goes unpenalised
                if (a > 0) {
                    g[a] += lambda * w[a]
                    h[a][a] += lambda
                }
                h[a][a] += 1e-9 * total
            }
            val step = solve(h, g)
            var largest = 0.0
            for (a in 0 until d) {
                w[a] -= step[a]
                largest = maxOf(largest, kotlin.math.abs(step[a]))
            }
            if (largest < 1e-6) return w
        }
        return w
    }

    /** Solves h · x = g by Gaussian elimination with partial pivoting. */
    private fun solve(h: Array<DoubleArray>, g: DoubleArray): DoubleArray {
        val n = g.size
        val a = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) h[i][j] else g[i] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { kotlin.math.abs(a[it][col]) }
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val p = a[col][col]
            if (kotlin.math.abs(p) < 1e-300) continue
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / p
                if (f == 0.0) continue
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(n) { i -> if (kotlin.math.abs(a[i][i]) < 1e-300) 0.0 else a[i][n] / a[i][i] }
    }
}
