package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.IntStream
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Scoring::class.java)

/**
 * THE SCORING OF THE ITEMS, APART FROM THE TASK — of every fold of the model ([BilinearSuggester.FOLDS]):
 * what carries an item's features to the [rank] coordinates ([a], itemWidth × rank, by the [design]),
 * the weights of a pair's own feature and bias ([pair]: the site gives the value as an example, the
 * common bias), and every value's coordinates ([vt], rank × m, transposed for the
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
     * judged as if the item did not say so. Its scores ([SuggestionScores.scores]): the logit of the
     * chance, of the values asked alone.
     */
    fun on(task: SuggestionTask): SuggestionScores = Scored(task)

    /**
     * THE SCORES OF THE ITEMS OF ONE TASK. Every item's coordinates by the model of every fold made
     * at the first asking, of one product of the whole task with the folds' A side by side: of the
     * items' own vectors where the task holds them, none gathered — a chunk of rows gathered, a fold
     * apart, copied gigabytes of vectors for every facet. A row's scores of the values asked alone —
     * the characters of a work's universes, not every character — the rows asking the same values
     * scored together; the chance made of a score only when it is asked for.
     */
    private inner class Scored(private val task: SuggestionTask) : SuggestionScores {
        private val m = valueKeys.size
        private val folds = BilinearSuggester.FOLDS
        private val width = rank * folds

        init {
            require(task.valueCount == m) { "${task.valueCount} values of a scoring of $m" }
        }

        /** Every item's fold, of its id. */
        private val foldOf: IntArray by lazy { IntArray(task.itemCount) { Problem.foldOf(task.itemIds[it], folds) } }

        /** Every item's coordinates by every fold's model: a row an item, the folds' [rank] each side by side. */
        private val coordinates: Floats by lazy {
            val started = System.nanoTime()
            val stacked = FloatArray(design.itemWidth * width)
            for (f in 0 until folds) for (c in 0 until design.itemWidth) for (q in 0 until rank) stacked[c * width + f * rank + q] = a[f][c * rank + q]
            // One product of the whole task, the library's threads on every physical core ([Blas.wide]): on its one
            // thread the features of the 75 630 works of fanfics.me took half a second a facet
            Floats(task.itemCount * width).also { out ->
                val features = design.features(task, IntArray(task.itemCount) { it })
                val weights = Floats.of(stacked)
                val laid = System.nanoTime()
                features.onesTimes(weights, width, out)
                val summed = System.nanoTime()
                Blas.wide { features.blocksTimes(weights, width, out) }
                if (task.itemCount >= LOGGED) {
                    val present = task.inputs.associate { it.name to it.present.count { p -> p } }
                    log.info(
                        "the coordinates of {} items of {} values, {} features, {} folds of rank {}: the weights and features laid out in {} ms; " +
                            "the ones, {} an item, summed in {} ms; the blocks {} multiplied in {} ms",
                        task.itemCount, m, design.itemWidth, folds, rank, (laid - started) / 1_000_000,
                        "%.1f".format(features.ones.sumOf { it.size }.toDouble() / task.itemCount), (summed - laid) / 1_000_000,
                        design.blocks.joinToString(", ", "[", "]") { "${it.name} ${it.dim} of ${present[it.name] ?: 0}" },
                        (System.nanoTime() - summed) / 1_000_000,
                    )
                }
            }
        }

        /**
         * The time of the scores of every value — of the rows asking every one, as the tags — by its parts,
         * summed over the askings: told in the log once every item was scored so.
         */
        private val gathering = AtomicLong()
        private val making = AtomicLong()
        private val wholeRows = AtomicInteger()

        override fun of(rows: IntArray): List<FloatArray> = arrayOfNulls<FloatArray>(rows.size).also { out ->
            scored(rows, null, chances = true, scratch = false) { k, row -> out[k] = row }
        }.map { it!! }

        override fun scores(rows: IntArray, columns: List<IntArray?>): List<FloatArray> = arrayOfNulls<FloatArray>(rows.size).also { out ->
            scored(rows, columns, chances = false, scratch = false) { k, row -> out[k] = row }
        }.map { it!! }

        /**
         * Every row made in a buffer of the block of rows it is of, handed over and made over by the next:
         * no array of every value for every work — of the tags of fanfics.me, 75 630 works of 8 894
         * values, 2.7 GB of them a pass.
         */
        override fun scores(rows: IntArray, columns: List<IntArray?>, each: (Int, FloatArray) -> Unit) {
            require(columns.size == rows.size) { "${columns.size} sets of values of ${rows.size} rows" }
            scored(rows, columns, chances = false, scratch = true, put = each)
        }

        override fun chance(score: Float): Float = Bilinear.sigmoid(score)

        /** σ(0) is one half, and σ never falls. */
        override val half: Float get() = 0f

        /**
         * Every row of [rows] scored of its values of [columns] (null: every value), or its chances, handed
         * to [put] with its place among [rows]: in an array of its own, or — [scratch] — in a buffer of the
         * block of rows it is of, made over by the next row of the block.
         */
        private fun scored(rows: IntArray, columns: List<IntArray?>?, chances: Boolean, scratch: Boolean, put: (Int, FloatArray) -> Unit) {
            val all = coordinates
            val fold = IntArray(rows.size) { foldOf[rows[it]] }
            for (f in 0 until folds) {
                val at = rows.indices.filter { fold[it] == f }.toIntArray()
                if (at.isEmpty()) continue
                // The rows asking one set of values together: one array is one set
                val groups = IdentityHashMap<IntArray?, MutableList<Int>>()
                at.forEach { k -> groups.getOrPut(columns?.get(k)) { ArrayList() } += k }
                groups.forEach { (cols, members) ->
                    val started = System.nanoTime()
                    val u = Floats(members.size * rank)
                    members.forEachIndexed { r, k -> u.copy(all, rows[k] * width + f * rank, r * rank, rank) }
                    if (cols == null) gathering.addAndGet(System.nanoTime() - started)
                    score(f, rows, members.toIntArray(), u, cols, chances, scratch, put)
                }
            }
            if (columns == null || columns.all { it == null }) {
                if (wholeRows.addAndGet(rows.size) == task.itemCount && task.itemCount >= LOGGED) log.info(
                    "the scores of every value of {} items of {} values: their coordinates gathered in {} ms, the rows made in {} ms",
                    task.itemCount, m, gathering.get() / 1_000_000, making.get() / 1_000_000,
                )
            }
        }

        /** The rows [members] (places in [rows]) of the fold [f], their coordinates [u]: their scores of the values [cols] (null: every value), or their chances, to [put]. */
        private fun score(
            f: Int, rows: IntArray, members: IntArray, u: Floats, cols: IntArray?, chances: Boolean, scratch: Boolean, put: (Int, FloatArray) -> Unit,
        ) {
            val n = members.size
            val k = cols?.size ?: m
            if (k == 0) {
                members.forEach { put(it, FloatArray(0)) }
                return
            }
            // Of the rows of every value, each row made where it is answered, of the coordinates and the side
            // ([Simd.combine]): a product of tens of thousands of works by thousands of values written into fresh
            // native memory, then copied out a row at a time, took the most of the tags' scores. Of the values
            // asked, the products of the rows in the native memory of the product, each row taken out of it once
            val product: Floats?
            val coords: FloatArray?
            if (cols == null) {
                product = null
                coords = FloatArray(n * rank).also { u.take(it, 0, 0, n * rank) }
            } else if (n.toLong() * k * rank <= DIRECT) {
                // A few rows of a few values: summed here, no product to set up
                val side = vt[f]
                val own = FloatArray(n * rank).also { u.take(it, 0, 0, n * rank) }
                product = Floats(n * k)
                coords = null
                for (r in 0 until n) for (c in 0 until k) {
                    var sum = 0f
                    for (q in 0 until rank) sum += own[r * rank + q] * side[q * m + cols[c]]
                    product[r * k + c] = sum
                }
            } else {
                val side = vt[f]
                val sub = FloatArray(rank * k)
                for (q in 0 until rank) for (c in 0 until k) sub[q * k + c] = side[q * m + cols[c]]
                product = Floats(n * k)
                coords = null
                Blas.product(u, rank, n, rank, Floats.of(sub), k, product)
            }
            // A value's place among those asked
            val place: (Int) -> Int = when {
                cols == null -> { v -> v }
                k <= FEW -> { v -> cols.indexOf(v) }
                else -> IntArray(m) { -1 }.also { at -> cols.forEachIndexed { c, v -> at[v] = c } }.let { at -> { v: Int -> at[v] } }
            }
            val w = pair[f]
            // Every value's bias with the common one, of the values asked: added to a row at once, in the wide registers
            val shift = FloatArray(k) { c -> bias[f][cols?.get(c) ?: c] + w[1] }
            // The rows side by side on every core, a block of them on each: of the tags, tens of thousands of works of
            // thousands of values each. Of [scratch], a buffer a block, its rows made in it one after another
            val each = { r: Int, buffer: FloatArray? ->
                val i = rows[members[r]]
                val row = buffer ?: FloatArray(k)
                if (product == null) Simd.combine(coords!!, r * rank, rank, vt[f], m, shift, row)
                else {
                    product.take(row, 0, r * k, k)
                    Simd.axpy(1f, shift, row)
                }
                task.examples[i].forEach { v -> place(v).let { if (it >= 0) row[it] += w[0] } }
                if (chances) for (c in 0 until k) row[c] = Bilinear.sigmoid(row[c])
                put(members[r], row)
            }
            val made = System.nanoTime()
            val blocks = if (n.toLong() * k <= DIRECT) 1 else minOf(n, BLOCKS)
            IntStream.range(0, blocks).let { if (blocks > 1) it.parallel() else it }.forEach { b ->
                val buffer = if (scratch) FloatArray(k) else null
                for (r in n * b / blocks until n * (b + 1) / blocks) each(r, buffer)
            }
            if (cols == null) making.addAndGet(System.nanoTime() - made)
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
         * The version of what [pack] writes: 4 — the pair's own weights without the counts of the
         * values' names in the texts; one of 3 read without them. One of another is not read: the next
         * refresh keeps it anew.
         */
        private const val FORMAT = 4

        private const val BUFFER = 1 shl 16

        /** The items scored at once for the shares. */
        private const val SHARE_CHUNK = 1000

        /** Rows × values × rank up to which the scores are summed by hand: a product is set up for more. */
        private const val DIRECT = 1 shl 16

        /** The blocks of rows made side by side: a few for every core, for none to wait for the slowest. */
        private val BLOCKS = 4 * Runtime.getRuntime().availableProcessors()

        /** The items of a task from which its scoring's times are told in the log: of a pass over a source, not of a page. */
        private const val LOGGED = 1000

        /** Values asked up to which a value's place among them is looked for by going through them. */
        private const val FEW = 32

        /** A scoring kept by [pack]; null of another version or of other bytes. */
        fun read(bytes: ByteArray): Scoring? = runCatching {
            DataInputStream(ByteArrayInputStream(bytes).buffered(BUFFER)).use { input ->
                // One of 3 read with the weight of the counts of names left out, as a state of the suggester ([Weights.withoutCounts])
                val format = input.readInt()
                if (format != FORMAT && format != FORMAT - 1) return null
                val design = Design.read(input)
                val rank = input.readInt()
                val keys = Design.readStrings(input)
                val parts = List(BilinearSuggester.FOLDS) { List(4) { Design.readFloats(input) } }
                val shares = Design.readFloats(input).takeIf { it.size == keys.size }
                val pair = parts.map { p -> p[1].let { w -> if (w.size == Bilinear.PAIR) w else floatArrayOf(w[0], w[w.size - 1]) } }
                Scoring(design, rank, keys, parts.map { it[0] }, pair, parts.map { it[2] }, parts.map { it[3] }, shares)
            }
        }.getOrNull()
    }
}
