package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.ln1p

/**
 * THE SCORING OF THE ITEMS, APART FROM THE TASK — of every fold of the model ([BilinearSuggester.FOLDS]):
 * what carries an item's features to the [rank] coordinates ([a], itemWidth × rank, by the [design]),
 * the weights of a pair's own features ([pair]: the site gives the value as an example, the texts
 * name it, the common bias), and every value's coordinates ([vt], rank × m, transposed for the
 * product: its names and groups carried by B, its own coordinates added) and bias, of the values
 * [valueKeys] in their order.
 *
 * Of the item, its features alone: the chance an item has a value is σ(xᵢA · v + b_v + the pair's)
 * — a product of the item's coordinates with the value's kept. Made once of the task fitted on,
 * kept beside the model; a page of twenty works is scored by it of their own features, no name and
 * no group of any value read. Of a few megabytes: of a facet of 7 080 characters and 8 077 features
 * of an item, every fold's A 0.5 MB, its values 0.5 MB.
 */
internal class Scoring(
    val design: Design, val rank: Int, val valueKeys: List<String>,
    val a: List<FloatArray>, val pair: List<FloatArray>, val vt: List<FloatArray>, val bias: List<FloatArray>,
    /**
     * Every value's share of the works the model was fitted on: the mean of their chances of it, as
     * the model scores them ([withShares]) — what it tells of the value over the works as a whole,
     * no work read ([weights]). None of a scoring kept before it was.
     */
    val shares: FloatArray? = null,
) {
    init {
        val m = valueKeys.size
        require(listOf(a, pair, vt, bias).all { it.size == BilinearSuggester.FOLDS }) { "Not a fold each" }
        require(vt.all { it.size == rank * m } && bias.all { it.size == m }) { "Not of the $m values" }
        require(a.all { it.size == design.itemWidth * rank }) { "Not of ${design.itemWidth} features" }
        require(shares == null || shares.size == m) { "${shares?.size} shares of $m values" }
    }

    /**
     * The scoring with every value's share of the items of [task] — the task it was fitted on: the
     * sum of their chances of it over their number, scored a thousand items at a time, none kept. An
     * item that may not have the value at all ([SuggestionTask.allowed]: a pairing of characters it
     * has not) adds nothing: the model never learns of such a pair, its score there tells nothing.
     */
    fun withShares(task: SuggestionTask): Scoring {
        val m = valueKeys.size
        val sums = DoubleArray(m)
        val scores = on(task)
        (0 until task.itemCount).chunked(SHARE_CHUNK).forEach { chunk ->
            scores.of(chunk.toIntArray()).forEachIndexed { r, row ->
                val allowed = task.allowed[chunk[r]]
                if (allowed == null) for (j in 0 until m) sums[j] += row[j]
                else {
                    allowed.forEach { j -> if (j in 0 until m) sums[j] += row[j] }
                    // Of those, the ones the item may not have after all
                    task.barred[chunk[r]].forEach { j -> if (j in 0 until m) sums[j] -= row[j] }
                }
            }
        }
        val n = task.itemCount.coerceAtLeast(1)
        return Scoring(design, rank, valueKeys, a, pair, vt, bias, FloatArray(m) { (sums[it] / n).toFloat() })
    }

    /**
     * The chances of every value of the items of [task] — of the values the scoring is of, in its
     * order. Every item by the model of the fold it was held out in, as fitted: a value the item has is
     * judged as if the item did not say so.
     */
    fun on(task: SuggestionTask): SuggestionScores {
        val m = valueKeys.size
        require(task.valueCount == m) { "${task.valueCount} values of a scoring of $m" }
        val sides = vt.map { Floats.of(it) }
        return SuggestionScores { rows ->
            val out = arrayOfNulls<FloatArray>(rows.size)
            val byFold = rows.indices.groupBy { Problem.foldOf(task.itemIds[rows[it]], BilinearSuggester.FOLDS) }
            byFold.forEach { (fold, at) ->
                val u = Floats(at.size * rank)
                design.features(task, IntArray(at.size) { rows[at[it]] }).times(Floats.of(a[fold]), rank, u)
                val s = Floats(at.size * m)
                Blas.product(u, rank, at.size, rank, sides[fold], m, s)
                val w = pair[fold]
                val b = bias[fold]
                at.forEachIndexed { r, k ->
                    val i = rows[k]
                    val row = FloatArray(m)
                    s.take(row, 0, r * m, m)
                    task.examples[i].forEach { row[it] += w[0] }
                    task.mentions[i]?.forEach { (v, count) -> if (v in 0 until m) row[v] += w[1] * ln1p(count.toDouble()).toFloat() }
                    for (j in 0 until m) row[j] = Bilinear.sigmoid(row[j] + b[j] + w[2])
                    out[k] = row
                }
            }
            out.map { it!! }
        }
    }

    /** Every value's weight over the works as a whole: its share of the works fitted on ([shares]); none of a scoring kept before they were. */
    fun weights(): DoubleArray? = shares?.let { s -> DoubleArray(s.size) { s[it].toDouble() } }

    /** Not compressed: numbers compress little, and a page waits for their reading. */
    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes.buffered(BUFFER)).use { out ->
            out.writeInt(FORMAT)
            design.write(out)
            out.writeInt(rank)
            Design.writeStrings(out, valueKeys)
            for (f in a.indices) listOf(a[f], pair[f], vt[f], bias[f]).forEach { Design.writeFloats(out, it) }
            Design.writeFloats(out, shares ?: FloatArray(0))
        }
        return bytes.toByteArray()
    }

    companion object {
        /**
         * The version of what [pack] writes: 2 with the values' shares ([shares]), 3 with the shares
         * of the items that may have each alone — those of 2 read as none, kept anew at the next refresh.
         */
        private const val FORMAT = 3

        private const val BUFFER = 1 shl 16

        /** The items scored at once for the shares. */
        private const val SHARE_CHUNK = 1000

        /** A scoring kept by [pack] — of the version before it, without the shares; null of another version or of other bytes. */
        fun read(bytes: ByteArray): Scoring? = runCatching {
            DataInputStream(ByteArrayInputStream(bytes).buffered(BUFFER)).use { input ->
                val format = input.readInt()
                if (format !in 1..FORMAT) return null
                val design = Design.read(input)
                val rank = input.readInt()
                val keys = Design.readStrings(input)
                val parts = List(BilinearSuggester.FOLDS) { List(4) { Design.readFloats(input) } }
                val shares = if (format < 2) null else Design.readFloats(input).takeIf { format >= 3 && it.size == keys.size }
                Scoring(design, rank, keys, parts.map { it[0] }, parts.map { it[1] }, parts.map { it[2] }, parts.map { it[3] }, shares)
            }
        }.getOrNull()
    }
}
