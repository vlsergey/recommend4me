package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the witnesses of [TagSuggester] say of every value for an item of a task, as the features
 * of the regression:
 *
 *     1                          the bias
 *     for every view of the texts (the description, the chapters, the site's own line of values):
 *       has                      the item has the view
 *       has · neighbours         the share of the value among the other items, each weighed by how
 *                                alike it is in the view
 *       has · name               how much nearer the view is to the value's name than usually (z) —
 *                                not of the site's own line of the values: it would repeat the site
 *     has values                 the item has two values of the facet or more
 *     · together, · its max      the mean and the largest pointwise mutual information of the
 *                                value with the item's other values of the facet
 *     has context                the item has values of other facets
 *     · context, · its max       the same with those: its fandom, its tags, its author
 *     counted                    the item's own texts were counted for names of the values
 *     · mentions                 log(1 + how many times they name the value)
 *     for every grouping of the values (the sexes of a pairing's characters):
 *       has context              the value is of a group and the item has values of other facets
 *       · context, · its max     the same as of the values, of the value's group with the context
 *     example                    the site gives the item the value as an example of the facet
 *                                ([SuggestionTask.examples]: its own tag of the work's tags)
 *     rarity                    the log share of the items having the value
 *
 * EVERY COUNT LEAVES THE ITEM OUT: what the item says of its own values does not vouch for them,
 * so a value the site gave it that nothing else supports comes out unlikely — but for a value the
 * site gives it as an example from a facet of its own, which is a witness by itself: how much one
 * is worth is learnt as any other.
 *
 * No number of the witnesses is chosen by hand. A neighbour weighs exp(z) by its likeness z in
 * standard deviations of the item's likeness to all the others — the item's own scale; counts are
 * drawn by half an item each way (the Jeffreys prior), so a value never met is not impossible.
 *
 * Built for one scoring and dropped with it.
 */
internal class Witnesses(private val task: SuggestionTask) {

    private val n = task.itemCount
    private val m = task.valueCount
    private val d = task.values.cols

    /** The values' name vectors as columns, d × m. */
    private val nameColumns: Floats = transposed(task.values.held, IntArray(m) { it })
    private val named = BooleanArray(m) { norm(task.values.held, task.values.row(it)) > 0.5 }

    private inner class View(val vectors: Matrix) {
        val present = BooleanArray(n) { norm(vectors.held, vectors.row(it)) > 0.5 }
        val rows = (0 until n).filter { present[it] }.toIntArray()

        /** The items having the view as columns, d × rows: one product gives a block its cosines to all. */
        val columns: Floats = transposed(vectors.held, rows)

        /** The usual nearness of the view to each value's name and its spread, over every item having the view. */
        val nameMean = DoubleArray(m)
        val nameSpread = DoubleArray(m)

        init {
            if (rows.size >= 2 && m > 0) {
                val sum = DoubleArray(m)
                val squares = DoubleArray(m)
                blocks(rows).forEach { block ->
                    val cos = cosines(vectors, block, nameColumns, m)
                    for (r in block.indices) for (v in 0 until m) {
                        val c = cos[r * m + v].toDouble()
                        sum[v] += c
                        squares[v] += c * c
                    }
                }
                for (v in 0 until m) {
                    nameMean[v] = sum[v] / rows.size
                    nameSpread[v] = sqrt((squares[v] / rows.size - nameMean[v] * nameMean[v]).coerceAtLeast(0.0))
                }
            }
        }
    }

    private val views = task.views.map { View(it.vectors) }

    private val assigned: List<IntArray> = task.assigned.map { it.distinct().sorted().toIntArray() }
    private val context: List<IntArray> = task.context.map { it.distinct().sorted().toIntArray() }

    /** Items having each value. */
    private val counts = IntArray(m).also { c -> assigned.forEach { a -> a.forEach { c[it]++ } } }

    /**
     * Pairs of a value of [sources] (of [width] values) and a target on one item — a value of the
     * facet, or a group of them ([targets], of [targetWidth]) — as sorted codes `u · targetWidth + v`,
     * with their counts; and the items having each source value and each target.
     */
    private inner class Pairs(
        val sources: List<IntArray>, width: Int, val sameFacet: Boolean,
        val targets: List<IntArray> = assigned, val targetWidth: Int = m,
    ) {
        val sourceCounts = IntArray(width).also { c -> sources.forEach { a -> a.forEach { c[it]++ } } }
        val targetCounts = IntArray(targetWidth).also { c -> targets.forEach { a -> a.forEach { c[it]++ } } }
        val codes: LongArray
        val pairCounts: IntArray

        init {
            val all = ArrayList<Long>()
            for (i in 0 until n) for (u in sources[i]) for (v in targets[i]) if (!(sameFacet && u == v)) all += u.toLong() * targetWidth + v
            val sorted = all.toLongArray().also { it.sort() }
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
            codes = unique.toLongArray()
            pairCounts = times.toIntArray()
        }

        private fun lowerBound(code: Long): Int {
            var lo = 0
            var hi = codes.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (codes[mid] < code) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * The mean and the largest mutual information of every target with the source values of
         * item [i], handed to [emit]; the item left out of every count. Nothing for a target
         * the item's sources say nothing of.
         */
        fun forEachTarget(i: Int, emit: (target: Int, mean: Double, best: Double) -> Unit) {
            val own = sources[i]
            // Of the facet's own values, an item needs two for every value to have another beside
            // it: with one, its own value would have none and every other value one — the witness
            // would tell the answer. So with fewer than two it says nothing of any value
            if (own.size < if (sameFacet) 2 else 1) return
            val has = targets[i].toHashSet()
            val items = (n - 1).coerceAtLeast(1).toDouble()
            val w = targetWidth.toLong()
            fun pmi(u: Int, v: Int, together: Int): Double {
                val both = (together - if (v in has) 1 else 0).coerceAtLeast(0)
                val expected = (sourceCounts[u] - 1).coerceAtLeast(0).toDouble() * (targetCounts[v] - if (v in has) 1 else 0) / items
                return ln((both + JEFFREYS) / (expected + JEFFREYS))
            }
            val sum = DoubleArray(targetWidth)
            val best = DoubleArray(targetWidth) { Double.NEGATIVE_INFINITY }
            val seen = IntArray(targetWidth)
            for (u in own) {
                // As if never together, then corrected for the pairs seen
                for (v in 0 until targetWidth) {
                    if (sameFacet && v == u) continue
                    val p = pmi(u, v, 0)
                    sum[v] += p
                    if (p > best[v]) best[v] = p
                    seen[v]++
                }
                var k = lowerBound(u.toLong() * w)
                while (k < codes.size && codes[k] / w == u.toLong()) {
                    val v = (codes[k] % w).toInt()
                    val real = pmi(u, v, pairCounts[k])
                    sum[v] += real - pmi(u, v, 0)
                    if (real > best[v]) best[v] = real
                    k++
                }
            }
            for (v in 0 until targetWidth) if (seen[v] > 0) emit(v, sum[v] / seen[v], best[v])
        }

        /** [forEachTarget] of the values themselves, written at [at] of [out]: the flag of having any, the mean, the largest. */
        fun write(i: Int, out: Array<FloatArray>, at: Int) = forEachTarget(i) { v, mean, best -> put(out[v], at, mean, best) }
    }

    private fun put(x: FloatArray, at: Int, mean: Double, best: Double) {
        x[at] = 1f
        x[at + 1] = mean.toFloat()
        x[at + 2] = best.toFloat()
    }

    /** The values together with the item's others, and with its context. */
    private val together = Pairs(assigned, m, sameFacet = true)
    private val withContext = Pairs(context, task.contextCount, sameFacet = false)

    /**
     * The groups of the values with the item's context, one witness a grouping: what goes with a
     * pairing of two men — the tag "slash" — goes with every one of them, a pairing never seen too.
     */
    private inner class Grouped(val grouping: ValueGrouping) {
        val members: List<IntArray> = (0 until grouping.groupCount).map { g -> (0 until m).filter { grouping.groupOf[it] == g }.toIntArray() }
        val pairs = Pairs(
            context, task.contextCount, sameFacet = false,
            targets = assigned.map { a -> a.map { grouping.groupOf[it] }.filter { it >= 0 }.distinct().toIntArray() },
            targetWidth = grouping.groupCount,
        )

        fun write(i: Int, out: Array<FloatArray>, at: Int) = pairs.forEachTarget(i) { g, mean, best -> members[g].forEach { v -> put(out[v], at, mean, best) } }
    }

    private val grouped = task.groupings.map { Grouped(it) }

    val features: Int = featuresOf(views.size, grouped.size)

    /**
     * [rows] in blocks of as many as fit [BLOCK_FLOATS] floats of cosines to every item: the
     * blocks change how much memory a pass takes, never what it finds.
     */
    fun blocks(rows: IntArray): List<IntArray> {
        val size = (BLOCK_FLOATS / maxOf(n, m, 1)).coerceAtLeast(1)
        return rows.toList().chunked(size).map { it.toIntArray() }
    }

    /**
     * The bit of the values of the other facets among the views: a work the site gave none of — no
     * tag, no fandom — is judged by weights learnt as if no work had them, not by weights that
     * lean on them.
     */
    private val contextBit = 1 shl views.size

    /** The bit of the examples among the views: a work the site gave no example of the facet is judged as if no work had any. */
    private val examplesBit = 1 shl (views.size + 1)

    /** The views item [i] has, a bit per view of the texts, one for the values of its other facets, one for its examples. */
    fun viewsOf(i: Int): Int {
        var set = views.indices.fold(0) { s, c -> if (views[c].present[i]) s or (1 shl c) else s }
        if (context[i].isNotEmpty()) set = set or contextBit
        if (task.examples[i].isNotEmpty()) set = set or examplesBit
        return set
    }

    /** Every set of views some item has. */
    fun viewSets(): Set<Int> = (0 until n).map(::viewsOf).toSet()

    /** Forgets in [values] (one item's features) what the views outside [set] say. */
    fun keepViews(values: Array<FloatArray>, set: Int) {
        views.indices.filter { set and (1 shl it) == 0 }.forEach { c -> values.forEach { f -> f.fill(0f, 1 + 3 * c, 1 + 3 * c + 3) } }
        if (set and contextBit == 0) {
            val own = 1 + 3 * views.size
            values.forEach { f ->
                f.fill(0f, own + 3, own + 6)
                grouped.indices.forEach { g -> f.fill(0f, own + 8 + 3 * g, own + 8 + 3 * g + 3) }
            }
        }
        if (set and examplesBit == 0) values.forEach { f -> f[features - 2] = 0f }
    }

    /**
     * Every pair of an item and a value, as an example of the views [set]: its features, whether
     * the item has the value, and how much the example weighs. AN ITEM WITH NO VALUE AT ALL SAYS
     * NOTHING of any: the site left it untagged, it did not deny it every value — unless the user
     * said no to one of them.
     */
    fun forEachExample(set: Int, visit: (FloatArray, Boolean, Double) -> Unit) {
        forEachExample { x, positive, weight ->
            keepViews(arrayOf(x), set)
            visit(x, positive, weight)
        }
    }

    /**
     * Every example with every view: of the items with a value or a word of the user, every value
     * the item may have ([SuggestionTask.allowed]) and every one it has. A value it has and one the
     * user rejected weigh 1; one it merely lacks [SuggestionTask.lackWeight].
     */
    private fun forEachExample(visit: (FloatArray, Boolean, Double) -> Unit) {
        blocks(labelled()).forEach { block ->
            features(block).forEachIndexed { b, values ->
                val i = block[b]
                val has = assigned[i].toHashSet()
                val denied = task.rejected[i].toHashSet()
                val may = task.allowed[i]?.toHashSet()
                values.forEachIndexed { v, x ->
                    if (may == null || v in may || v in has) visit(x, v in has, if (v in has || v in denied) 1.0 else task.lackWeight)
                }
            }
        }
    }

    private fun labelled(): IntArray = (0 until n).filter { assigned[it].isNotEmpty() || task.rejected[it].isNotEmpty() }.toIntArray()

    /** How many examples there are, of every set of views alike. */
    fun exampleCount(): Long = labelled().sumOf { i ->
        val may = task.allowed[i]?.toHashSet() ?: return@sumOf m.toLong()
        (may + assigned[i].toSet()).size.toLong()
    }

    /**
     * Every example worked out once and held: a fitting reads them at every step of every set of
     * views, and working them out — the neighbours, the pairs — costs far more than reading them.
     */
    fun hold(): HeldExamples {
        val count = exampleCount().toInt()
        val xs = FloatArray(count * features)
        val positive = BooleanArray(count)
        val weights = DoubleArray(count)
        var k = 0
        forEachExample { x, has, weight ->
            x.copyInto(xs, k * features)
            positive[k] = has
            weights[k] = weight
            k++
        }
        return HeldExamples(xs, positive, weights, features) { x, set -> keepViews(arrayOf(x), set) }
    }

    /** Examples held in memory: each one handed over as the views of a set see it. */
    class HeldExamples(
        private val xs: FloatArray, private val positive: BooleanArray, private val weights: DoubleArray, private val features: Int,
        private val keep: (FloatArray, Int) -> Unit,
    ) {
        fun forEach(set: Int, visit: (FloatArray, Boolean, Double) -> Unit) {
            val x = FloatArray(features)
            for (k in positive.indices) {
                xs.copyInto(x, 0, k * features, (k + 1) * features)
                keep(x, set)
                visit(x, positive[k], weights[k])
            }
        }
    }

    /** The features of every value for each of [rows]: rows × values × [features]. */
    fun features(rows: IntArray): List<Array<FloatArray>> {
        class Block(val position: Map<Int, Int>, val toItems: Floats?, val toNames: Floats?)
        val blocks = views.map { view ->
            val encoded = rows.filter { view.present[it] }.toIntArray()
            Block(
                encoded.withIndex().associate { (k, i) -> i to k },
                if (encoded.isNotEmpty() && view.rows.isNotEmpty()) cosines(view.vectors, encoded, view.columns, view.rows.size) else null,
                if (encoded.isNotEmpty() && m > 0) cosines(view.vectors, encoded, nameColumns, m) else null,
            )
        }
        return rows.map { i ->
            val has = assigned[i].toHashSet()
            val items = (n - if (assigned[i].isNotEmpty()) 1 else 0).coerceAtLeast(1)
            fun countOf(v: Int) = counts[v] - if (v in has) 1 else 0
            val out = Array(m) { FloatArray(features) }
            for (v in 0 until m) {
                out[v][0] = 1f
                out[v][features - 1] = ln((countOf(v) + JEFFREYS) / (items + 2 * JEFFREYS)).toFloat()
            }
            views.forEachIndexed { c, view ->
                val block = blocks[c]
                val k = block.position[i] ?: return@forEachIndexed
                val at = 1 + 3 * c
                val line = FloatArray(view.rows.size)
                block.toItems?.take(line, 0, k * view.rows.size, view.rows.size)
                val share = neighbours(i, view, line)
                for (v in 0 until m) {
                    out[v][at] = 1f
                    out[v][at + 1] = share[v].toFloat()
                    if (!task.views[c].ofValues && block.toNames != null && named[v] && view.nameSpread[v] > 0) {
                        out[v][at + 2] = ((block.toNames[k * m + v] - view.nameMean[v]) / view.nameSpread[v]).toFloat()
                    }
                }
            }
            val own = 1 + 3 * views.size
            together.write(i, out, own)
            withContext.write(i, out, own + 3)
            // How often the item's own texts name the value
            task.mentions[i]?.let { named ->
                val at = own + 6
                for (v in 0 until m) {
                    out[v][at] = 1f
                    out[v][at + 1] = ln(1.0 + (named[v] ?: 0)).toFloat()
                }
            }
            grouped.forEachIndexed { g, it -> it.write(i, out, own + 8 + 3 * g) }
            task.examples[i].forEach { v -> out[v][features - 2] = 1f }
            out
        }
    }

    /**
     * The share of each value among the items having [view] but [i] ([line] — its cosines to them),
     * each weighed exp(z), z its cosine in standard deviations from the mean of [line].
     */
    private fun neighbours(i: Int, view: View, line: FloatArray): DoubleArray {
        val share = DoubleArray(m)
        var sum = 0.0
        var squares = 0.0
        var others = 0
        line.forEachIndexed { j, c ->
            if (view.rows[j] == i) return@forEachIndexed
            sum += c
            squares += c.toDouble() * c
            others++
        }
        if (others == 0) return share
        val mean = sum / others
        val spread = sqrt((squares / others - mean * mean).coerceAtLeast(0.0))
        // The largest z first taken out, so the exponents do not overflow
        var top = Double.NEGATIVE_INFINITY
        line.forEachIndexed { j, c -> if (view.rows[j] != i) top = maxOf(top, if (spread > 0) (c - mean) / spread else 0.0) }
        var total = 0.0
        line.forEachIndexed { j, c ->
            if (view.rows[j] == i) return@forEachIndexed
            val w = exp((if (spread > 0) (c - mean) / spread else 0.0) - top)
            total += w
            assigned[view.rows[j]].forEach { v -> share[v] += w }
        }
        for (v in 0 until m) share[v] /= total
        return share
    }

    /** The length of the row starting at [at] of [f]. */
    private fun norm(f: Floats, at: Int): Double {
        var s = 0.0
        for (q in 0 until d) {
            val x = f[at + q].toDouble()
            s += x * x
        }
        return sqrt(s)
    }

    /** The rows [rows] of [source] as columns, d × rows. */
    private fun transposed(source: Floats, rows: IntArray): Floats {
        val out = Floats(d * rows.size)
        val row = FloatArray(d)
        rows.forEachIndexed { j, i ->
            source.take(row, 0, i * d, d)
            for (q in 0 until d) out[q * rows.size + j] = row[q]
        }
        return out
    }

    /** The cosines of the rows [rows] of [vectors] to the [width] columns of [columns]: rows × width. */
    private fun cosines(vectors: Matrix, rows: IntArray, columns: Floats, width: Int): Floats {
        val block = vectors.gather(rows)
        val out = Floats(rows.size * width)
        Blas.product(block.held, d, rows.size, d, columns, width, out)
        return out
    }

    companion object {
        /** The bias, three of every view, three of the facet's own values, three of the context, two of the mentions, three of every grouping, the example, the rarity. */
        fun featuresOf(views: Int, groupings: Int) = 1 + 3 * views + 3 + 3 + 2 + 3 * groupings + 1 + 1

        /** Half an item drawn to every count each way: the Jeffreys prior of a share. */
        private const val JEFFREYS = 0.5

        /** The floats of cosines a block holds at once: memory, not meaning. */
        private const val BLOCK_FLOATS = 16 * 1024 * 1024
    }
}
