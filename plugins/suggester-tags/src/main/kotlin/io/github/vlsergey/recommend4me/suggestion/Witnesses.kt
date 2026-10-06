package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the three witnesses of [TagSuggester] say of every value for an item of a task, as the
 * features of the regression:
 *
 *     0  1                       the bias
 *     1  hasText                 the item's text is encoded
 *     2  hasText · neighbours    the logit of the share of the nearest texts having the value
 *     3  hasText · name          how much nearer the text is to the value's name than texts usually are (z)
 *     4  hasTags                 the item has other values
 *     5  hasTags · together      the mean pointwise mutual information of the value with the item's others
 *     6  hasTags · together max  the largest of them
 *     7  rarity                  the log share of the items having the value
 *
 * EVERY COUNT LEAVES THE ITEM OUT: what the item says of its own values does not vouch for them,
 * so a value the site gave it that nothing else supports comes out unlikely.
 *
 * Built for one scoring and dropped with it: the catalogue transposed for the products, the counts
 * of the values and of their pairs.
 */
internal class Witnesses(private val task: SuggestionTask) {

    private val n = task.itemCount
    private val m = task.valueCount
    private val d = task.items.cols

    private val hasText = BooleanArray(n) { norm(task.items.held, task.items.row(it)) > 0.5 }
    private val textRows = (0 until n).filter { hasText[it] }.toIntArray()

    /** The encoded items' vectors as columns, d × (their count): one product gives a block its cosines to all. */
    private val itemColumns: Floats = transposed(task.items.held, textRows, d)

    /** The values' name vectors as columns, d × m. */
    private val nameColumns: Floats = transposed(task.values.held, IntArray(m) { it }, d)

    private val assigned: List<IntArray> = task.assigned.map { it.distinct().sorted().toIntArray() }

    /** Items having each value, and the items having any. */
    private val counts = IntArray(m).also { c -> assigned.forEach { a -> a.forEach { c[it]++ } } }
    private val tagged = assigned.count { it.isNotEmpty() }

    /** Pairs of values together on an item, as sorted codes `u · m + v` (both orders) with their counts. */
    private val pairCodes: LongArray
    private val pairCounts: IntArray

    init {
        val codes = ArrayList<Long>()
        assigned.forEach { a ->
            for (u in a) for (v in a) if (u != v) codes += u.toLong() * m + v
        }
        val sorted = codes.toLongArray().also { it.sort() }
        val unique = ArrayList<Long>()
        val times = ArrayList<Int>()
        var k = 0
        while (k < sorted.size) {
            var e = k
            while (e < sorted.size && sorted[e] == sorted[k]) e++
            unique += sorted[k]
            times += e - k
            k = e
        }
        pairCodes = unique.toLongArray()
        pairCounts = times.toIntArray()
    }

    /** The usual nearness of a text to each value's name and its spread, over a sample of the texts. */
    private val nameMean = DoubleArray(m)
    private val nameSpread = DoubleArray(m) { 1.0 }

    init {
        val sample = textRows.toList().shuffled(kotlin.random.Random(7)).take(NAME_SAMPLE).toIntArray()
        if (sample.size >= 2 && m > 0) {
            val cos = cosines(sample, nameColumns, m)
            val sum = DoubleArray(m)
            val squares = DoubleArray(m)
            for (r in sample.indices) for (v in 0 until m) {
                val c = cos[r * m + v].toDouble()
                sum[v] += c
                squares[v] += c * c
            }
            for (v in 0 until m) {
                nameMean[v] = sum[v] / sample.size
                nameSpread[v] = sqrt((squares[v] / sample.size - nameMean[v] * nameMean[v]).coerceAtLeast(1e-6))
            }
        }
    }

    /** The features of every value for each of [rows]: rows × values × [FEATURES]. */
    fun features(rows: IntArray): List<Array<FloatArray>> {
        val encoded = rows.filter { hasText[it] }.toIntArray()
        val position = encoded.withIndex().associate { (k, i) -> i to k }
        val toItems = if (encoded.isNotEmpty() && textRows.isNotEmpty()) cosines(encoded, itemColumns, textRows.size) else null
        val toNames = if (encoded.isNotEmpty() && m > 0) cosines(encoded, nameColumns, m) else null
        val line = FloatArray(textRows.size)
        return rows.map { i ->
            val own = assigned[i]
            val ownSet = own.toHashSet()
            // Every count without the item itself
            val items = (tagged - if (own.isNotEmpty()) 1 else 0).coerceAtLeast(1)
            fun countOf(v: Int) = counts[v] - if (v in ownSet) 1 else 0
            val out = Array(m) { FloatArray(FEATURES) }
            for (v in 0 until m) {
                out[v][0] = 1f
                out[v][7] = ln((countOf(v) + 1.0) / (items + 2.0)).toFloat()
            }
            val k = position[i]
            if (k != null && toItems != null && toNames != null) {
                toItems.take(line, 0, k * textRows.size, textRows.size)
                val share = neighbours(i, line, items) { v -> countOf(v) }
                for (v in 0 until m) {
                    val z = (toNames[k * m + v] - nameMean[v]) / nameSpread[v]
                    out[v][1] = 1f
                    out[v][2] = ln(share[v] / (1 - share[v])).toFloat()
                    out[v][3] = if (norm(task.values.held, task.values.row(v)) > 0.5) z.toFloat() else 0f
                }
            }
            together(own, items, ::countOf, out)
            out
        }
    }

    /** The share of the [NEIGHBOURS] texts nearest to item [i] ([line] — its cosines to all texts) having each value, drawn towards its share in all. */
    private fun neighbours(i: Int, line: FloatArray, items: Int, countOf: (Int) -> Int): DoubleArray {
        val nearest = top(line, NEIGHBOURS + 1).filter { textRows[it] != i }.take(NEIGHBOURS)
        val share = DoubleArray(m)
        if (nearest.isEmpty()) {
            for (v in 0 until m) share[v] = (countOf(v) + 1.0) / (items + 2.0)
            return share
        }
        val best = line[nearest[0]]
        var total = 0.0
        nearest.forEach { j ->
            val w = exp(((line[j] - best) / TEMPERATURE).toDouble())
            total += w
            assigned[textRows[j]].forEach { v -> share[v] += w }
        }
        for (v in 0 until m) {
            val prior = (countOf(v) + 1.0) / (items + 2.0)
            share[v] = ((share[v] + PRIOR_WEIGHT * prior) / (total + PRIOR_WEIGHT)).coerceIn(1e-4, 1 - 1e-4)
        }
        return share
    }

    /** Features 4..6: how the value goes with the item's other values. */
    private fun together(own: IntArray, items: Int, countOf: (Int) -> Int, out: Array<FloatArray>) {
        if (own.isEmpty()) return
        val sum = DoubleArray(m)
        val best = DoubleArray(m) { Double.NEGATIVE_INFINITY }
        val ownSet = own.toHashSet()
        // The pairs of the item itself are taken out of the counts
        fun pmi(u: Int, v: Int, together: Int): Double {
            val both = (together - if (v in ownSet) 1 else 0).coerceAtLeast(0)
            val expected = countOf(u).toDouble() * countOf(v) / items
            return ln((both + SMOOTHING) / (expected + SMOOTHING))
        }
        val seen = IntArray(m)
        for (u in own) {
            // Values never seen with u
            for (v in 0 until m) if (v != u) {
                val p = pmi(u, v, 0)
                sum[v] += p
                if (p > best[v]) best[v] = p
                seen[v]++
            }
            // Corrected for those seen with it
            var at = lowerBound(u.toLong() * m)
            while (at < pairCodes.size && pairCodes[at] / m == u.toLong()) {
                val v = (pairCodes[at] % m).toInt()
                val zero = pmi(u, v, 0)
                val real = pmi(u, v, pairCounts[at])
                sum[v] += real - zero
                if (real > best[v]) best[v] = real
                at++
            }
        }
        for (v in 0 until m) {
            if (seen[v] == 0) continue
            out[v][4] = 1f
            out[v][5] = (sum[v] / seen[v]).toFloat()
            out[v][6] = best[v].toFloat()
        }
    }

    /** The length of the row of [d] numbers of [f] starting at [at]. */
    private fun norm(f: Floats, at: Int): Double {
        var s = 0.0
        for (q in 0 until d) {
            val x = f[at + q].toDouble()
            s += x * x
        }
        return sqrt(s)
    }

    private fun lowerBound(code: Long): Int {
        var lo = 0
        var hi = pairCodes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pairCodes[mid] < code) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The cosines of the items [rows] to the [width] columns of [columns]: rows × width. */
    private fun cosines(rows: IntArray, columns: Floats, width: Int): Floats {
        val block = task.items.gather(rows)
        val out = Floats(rows.size * width)
        Blas.product(block.held, d, rows.size, d, columns, width, out)
        return out
    }

    companion object {
        const val FEATURES = 8
        private const val NEIGHBOURS = 25

        /** How sharply the nearest of the neighbours outweigh the farther, in cosine. */
        private const val TEMPERATURE = 0.03f

        /** How many neighbours' worth the share in the whole catalogue is worth. */
        private const val PRIOR_WEIGHT = 1.0

        /** Pairs added to every count of the mutual information, so a rare pair says little. */
        private const val SMOOTHING = 0.5

        private const val NAME_SAMPLE = 2_000

        private fun transposed(source: Floats, rows: IntArray, d: Int): Floats {
            val out = Floats(d * rows.size)
            val row = FloatArray(d)
            rows.forEachIndexed { j, i ->
                source.take(row, 0, i * d, d)
                for (q in 0 until d) out[q * rows.size + j] = row[q]
            }
            return out
        }

        /** The indices of the [k] largest of [line], the largest first. */
        private fun top(line: FloatArray, k: Int): List<Int> {
            if (line.isEmpty()) return emptyList()
            val heap = java.util.PriorityQueue<Int>(k + 1, compareBy { line[it] })
            for (j in line.indices) {
                if (heap.size < k) heap += j
                else if (line[j] > line[heap.peek()]) {
                    heap.poll()
                    heap += j
                }
            }
            return heap.sortedByDescending { line[it] }
        }
    }
}
