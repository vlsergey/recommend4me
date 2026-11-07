package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
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
import kotlin.concurrent.withLock

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
        scores.close()
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

    /** Every fold's A side by side, as the products of the coordinates read them: of a feature its row, the folds' [rank] each one after another. */
    private val stacked: FloatArray = FloatArray(design.itemWidth * rank * BilinearSuggester.FOLDS).also { out ->
        val width = rank * BilinearSuggester.FOLDS
        for (f in a.indices) for (c in 0 until design.itemWidth) for (q in 0 until rank) out[c * width + f * rank + q] = a[f][c * rank + q]
    }

    /**
     * THE SCORES OF THE ITEMS OF ONE TASK. Every item's coordinates by the model of every fold made
     * at the first asking, of one product of the whole task with the folds' A side by side: of the
     * items' own vectors where the task holds them — the rows of the items that have a block — none
     * gathered. A row's scores of the values asked alone — the characters of a work's universes, not
     * every character — the rows asking the same values scored together; the chance made of a score
     * only when it is asked for.
     *
     * ON THE CARD WHEN THE TASK'S INPUTS ARE THERE ([InputBlock.card]): the coordinates made there and
     * kept there — brought over too, for the few values asked of a row — and what every row is shown
     * with chosen there ([shown]): of the tags of fanfics.me 8 894 scores a work made where they are
     * chosen, the few chosen brought back. Freed by [close], or with the task's inputs at the latest.
     */
    internal inner class Scored(private val task: SuggestionTask) : SuggestionScores {
        private val m = valueKeys.size
        private val folds = BilinearSuggester.FOLDS
        private val width = rank * folds

        init {
            require(task.valueCount == m) { "${task.valueCount} values of a scoring of $m" }
        }

        /** Every item's fold, of its id. */
        private val foldOf: IntArray by lazy { IntArray(task.itemCount) { Problem.foldOf(task.itemIds[it], folds) } }

        private val features: ItemFeatures by lazy { design.features(task) }

        /** The card the task's every block of the layout is on, with the kernels of the features; null: the main memory. */
        private var card: OnCard? = run {
            val blocks = features.blocks
            val cublas = blocks.firstOrNull()?.card?.cublas ?: return@run null
            if (rank > FeatureKernels.MAX_RANK || blocks.any { it.card?.cublas !== cublas }) return@run null
            val kernels = FeatureKernels.of(cublas) ?: return@run null
            runCatching { cublas.lock.withLock { OnCard(cublas, kernels) } }
                .onFailure { log.warn("no room on the card for the scores of {} items of {} values: they are made in the main memory", task.itemCount, m) }
                .getOrNull()?.also { c -> blocks.forEach { it.card!!.alongside(c) } }
        }

        /** Every item's coordinates by every fold's model, in the main memory: a row an item, the folds' [rank] each side by side. */
        private val coordinates: Floats by lazy {
            val started = System.nanoTime()
            val out = Floats(task.itemCount * width)
            val c = card
            val made = c?.let { runCatching { it.cublas.lock.withLock { it.coordinates(out) } }.getOrNull() }
            if (made == null) {
                c?.let { dropCard() }
                val weights = Floats.of(stacked)
                val laid = System.nanoTime()
                features.onesTimes(weights, width, out)
                val summed = System.nanoTime()
                // The library's threads on every physical core ([Blas.wide]): on its one thread the features of the
                // 75 630 works of fanfics.me took half a second a facet
                Blas.wide { features.blocksTimes(weights, width, out, features.scratchOf(width)) }
                if (task.itemCount >= LOGGED) log.info(
                    "the coordinates of {} items of {} values, {} features, {} folds of rank {}, in the main memory: the weights laid out in {} ms; " +
                        "the ones, {} an item, summed in {} ms; the blocks {} multiplied in {} ms",
                    task.itemCount, m, design.itemWidth, folds, rank, (laid - started) / 1_000_000, onesPerItem(), (summed - laid) / 1_000_000,
                    blocksTold(), (System.nanoTime() - summed) / 1_000_000,
                )
            } else if (task.itemCount >= LOGGED) log.info(
                "the coordinates of {} items of {} values, {} features, {} folds of rank {}, on the card: the weights and ones sent in {} ms, " +
                    "the ones, {} an item, summed and the blocks {} multiplied and brought over in {} ms",
                task.itemCount, m, design.itemWidth, folds, rank, made[0], onesPerItem(), blocksTold(), made[1],
            )
            out
        }

        private fun onesPerItem() = "%.1f".format(features.ones.sumOf { it.size }.toDouble() / task.itemCount.coerceAtLeast(1))

        private fun blocksTold(): String {
            val names = design.blocks.indices.associate { design.blockOffsets[it] to design.blocks[it].name }
            return features.blocks.joinToString(", ", "[", "]") { b -> "${names[b.mark]} ${b.dim} of ${b.count}" }
        }

        /** Whether the scores are made on the card. */
        internal val onTheCard: Boolean get() = card != null

        /** The likely of a row the one pass on the card keeps in its stripe ([FeatureKernels.STRIPE]): a test makes it small, for rows of more to be written again. */
        internal var stripe: Int = FeatureKernels.STRIPE

        /** The card given up — no room on it, a kernel that failed — and freed: the main memory's from now on. */
        private fun dropCard() {
            card?.close()
            card = null
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

        /** On the card when the coordinates are there and it chooses as many ([FeatureKernels.MAX_LEAST]); else of the rows handed over. */
        override fun shown(rows: IntArray, columns: List<IntArray?>, closed: List<IntArray>, least: Int): List<Shown> {
            require(columns.size == rows.size && closed.size == rows.size) { "${columns.size} sets of values, ${closed.size} closed of ${rows.size} rows" }
            coordinates
            val c = card
            if (c != null && least <= FeatureKernels.MAX_LEAST) {
                runCatching { c.cublas.lock.withLock { c.shown(rows, columns, closed, least) } }.onSuccess { return it }.onFailure { e ->
                    log.warn("what {} rows are shown with not chosen on the card ({}): in the main memory from now on", rows.size, e.message ?: e.javaClass.simpleName)
                    dropCard()
                }
            }
            return super.shown(rows, columns, closed, least)
        }

        override fun chance(score: Float): Float = Bilinear.sigmoid(score)

        /** σ(0) is one half, and σ never falls. */
        override val half: Float get() = 0f

        override fun close() = dropCard()

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

        /**
         * THE SCORING'S NUMBERS ON THE CARD: every item's coordinates ([u], made there by [coordinates]),
         * every fold's values' coordinates, shifts and example weight, every item's fold; and the rooms
         * of what a choosing sends and takes back, grown as asked. Under the card's lock, every call.
         */
        private inner class OnCard(val cublas: Cublas, val kernels: CudaKernels) : AutoCloseable {
            private val made = ArrayList<AutoCloseable>()
            private val rooms = HashMap<String, Cublas.DeviceBytes>()
            private val pins = HashMap<String, Cublas.Pinned>()
            private var closed = false

            private fun floats(count: Long): Cublas.DeviceFloats = (cublas.allocate(maxOf(1L, count)) ?: throw NoRoom()).also { made += it }

            /** The room [name], of [bytes] at least: kept for the next choosing, grown to twice when it is not enough. */
            private fun room(name: String, bytes: Long): Cublas.DeviceBytes {
                val have = rooms[name]
                if (have != null && have.bytes >= bytes) return have
                have?.close()
                return (cublas.allocateBytes(maxOf(bytes, 2 * (have?.bytes ?: 0), 64)) ?: throw NoRoom()).also { rooms[name] = it }
            }

            private fun ints(name: String, values: IntArray) = room(name, values.size.toLong() * Int.SIZE_BYTES).also { it.upload(values) }

            private val u = floats(task.itemCount.toLong() * width)
            private val vts = floats(folds.toLong() * rank * m).also { d -> for (f in 0 until folds) d.upload(Floats.of(vt[f]), 0, f.toLong() * rank * m, rank * m) }
            private val shifts = floats(folds.toLong() * m).also { d ->
                for (f in 0 until folds) d.upload(Floats.of(FloatArray(m) { bias[f][it] + pair[f][1] }), 0, f.toLong() * m, m)
            }
            private val examples = room("examples", folds.toLong() * Float.SIZE_BYTES).also { it.upload(FloatArray(folds) { f -> pair[f][0] }) }
            private val folded = (cublas.allocateBytes(task.itemCount.toLong() * Int.SIZE_BYTES) ?: throw NoRoom()).also { made += it; it.upload(foldOf) }

            /**
             * Every item's coordinates made in [u] — the ones summed of the stacked weights, every block's rows
             * multiplied and carried onto their items' rows — and brought over into [out]. The milliseconds of
             * the sending, and of the products with the bringing.
             */
            fun coordinates(out: Floats): LongArray {
                val started = System.nanoTime()
                val n = task.itemCount
                val weights = floats(stacked.size.toLong()).also { it.upload(Floats.of(stacked), 0, 0, stacked.size) }
                val (at, flat) = features.onesFlat()
                val onesAt = ints("onesAt", at)
                val ones = ints("ones", flat)
                val part = floats(features.widest.toLong() * width)
                // A block's rows, kept in half precision, made single a run at a time for the library to multiply
                val single = floats(minOf(FeatureKernels.CARD_ROWS, features.widest).toLong() * features.longest)
                val sent = System.nanoTime()
                kernels.launch("ones_rows", n.toLong() * width, n, width, onesAt, ones, weights, u)
                features.blocks.forEach { b ->
                    val rows = b.card!!
                    var from = 0
                    while (from < b.count) {
                        val many = minOf(FeatureKernels.CARD_ROWS, b.count - from)
                        FeatureKernels.floats(kernels, rows, from, many, single)
                        cublas.gemm(false, false, many, width, b.dim, single, 0, b.dim, weights, b.column.toLong() * width, width, part, from.toLong() * width, width, alpha = b.scale)
                        from += many
                    }
                    kernels.launch("scatter_rows", b.count.toLong() * width, b.count, width, part, rows.items, u)
                }
                // Brought over: the copy waits for the products before it
                u.download(out, 0, 0, n * width)
                val multiplied = System.nanoTime()
                // The weights, the block's part and its single rows are of the products alone
                listOf(weights, part, single).forEach {
                    it.close()
                    made.remove(it)
                }
                return longArrayOf((sent - started) / 1_000_000, (multiplied - sent) / 1_000_000)
            }

            /** The page-locked room [name] of the main memory, of [bytes] at least: kept for the next choosing, grown to twice when it is not enough. */
            private fun pinned(name: String, bytes: Long): Cublas.Pinned {
                val have = pins[name]
                if (have != null && have.bytes >= bytes) return have
                have?.close()
                return (cublas.pinned(maxOf(bytes, 2 * (have?.bytes ?: 0), 64)) ?: throw NoRoom()).also { pins[name] = it }
            }

            /** Of [lists] one after another, laid out in [into] from int [at] on: where each begins among them, of their number + 1, then all of them; the ints written. */
            private fun laid(into: IntArray, at: Int, count: Int, lists: (Int) -> IntArray): Int {
                val values = at + count + 1
                var n = 0
                for (r in 0 until count) {
                    into[at + r] = n
                    val list = lists(r)
                    list.copyInto(into, values + n)
                    n += list.size
                }
                into[at + count] = n
                return count + 1 + n
            }

            /**
             * [SuggestionScores.shown] on the card, of every row at once: what the kernels read sent in one copy,
             * from page-locked memory; ONE PASS over a row (`shown_one`) — its likely into its stripe, the highest
             * of the rest merged by the warps' registers; the stripes packed (`shown_pack`); a row of more likely
             * than a stripe holds written again whole (`shown_likely`); what is chosen brought back in one copy.
             */
            fun shown(rows: IntArray, columns: List<IntArray?>, closed: List<IntArray>, least: Int): List<Shown> {
                check(!this.closed) { "the scores on the card are freed" }
                val started = System.nanoTime()
                val count = rows.size
                if (count == 0) return emptyList()
                // Every array of values once, every row by where its array begins (-1: every value)
                val arrays = IdentityHashMap<IntArray, Int>()
                var total = 0
                val colAt = IntArray(count)
                val colLen = IntArray(count)
                columns.forEachIndexed { r, cols ->
                    if (cols == null) colAt[r] = -1 else {
                        colAt[r] = arrays.getOrPut(cols) { total.also { total += cols.size } }
                        colLen[r] = cols.size
                    }
                }
                // What the kernels read, ints one after another: the items, where each row's values begin and how
                // many, the values, the closed and the examples by where each row's begin
                val closedSize = (0 until count).sumOf { closed[it].size }
                val exSize = (0 until count).sumOf { task.examples[rows[it]].size }
                val ins = IntArray(3 * count + total + 2 * (count + 1) + closedSize + exSize)
                rows.copyInto(ins, 0)
                colAt.copyInto(ins, count)
                colLen.copyInto(ins, 2 * count)
                val colsAt = 3 * count
                arrays.forEach { (a, at) -> a.copyInto(ins, colsAt + at) }
                val closedFrom = colsAt + total
                val closedInts = laid(ins, closedFrom, count) { closed[it] }
                val exFrom = closedFrom + closedInts
                laid(ins, exFrom, count) { task.examples[rows[it]] }
                val inBytes = ins.size.toLong() * Int.SIZE_BYTES
                val staging = pinned("in", inBytes)
                staging.put(0, ins)
                val input = room("in", inBytes)
                input.upload(staging, inBytes)
                fun at(int: Int) = input.at(int.toLong() * Int.SIZE_BYTES)
                val args = arrayOf<Any>(
                    at(0), folded, u, width, rank, vts, shifts, examples, m,
                    at(count), at(2 * count), at(colsAt), at(closedFrom), at(closedFrom + count + 1), at(exFrom), at(exFrom + count + 1), half,
                )
                // What it gives: of every row how many are likely and how many of the highest besides, their places and scores
                val l = maxOf(least, 0)
                val outInts = 2L * count + 2L * count * l
                val output = room("out", outInts * Int.SIZE_BYTES)
                fun out(int: Long) = output.at(int * Int.SIZE_BYTES)
                val stripePlace = room("stripePlace", count.toLong() * stripe * Int.SIZE_BYTES)
                val stripeScore = room("stripeScore", count.toLong() * stripe * Float.SIZE_BYTES)
                val sent = System.nanoTime()
                // A block of the kernel's threads a row
                kernels.launch(
                    "shown_one", count.toLong() * CudaKernels.BLOCK, *args, least, stripe,
                    out(0), out(count.toLong()), out(2L * count), out(2L * count + count.toLong() * l), stripePlace, stripeScore,
                )
                val back = pinned("out", outInts * Int.SIZE_BYTES)
                output.download(back, outInts * Int.SIZE_BYTES)
                val likelyOf = back.ints(0, count)
                val takenOf = back.ints(count.toLong() * Int.SIZE_BYTES, count)
                val tops = back.ints(2L * count * Int.SIZE_BYTES, count * l)
                val topScores = back.floats((2L * count + count.toLong() * l) * Int.SIZE_BYTES, count * l)
                val chosen = System.nanoTime()
                // The stripes packed; the rows of more likely than a stripe holds written again whole after them
                val packedAt = IntArray(count)
                var packed = 0
                val over = ArrayList<Int>()
                for (r in 0 until count) {
                    packedAt[r] = packed
                    packed += minOf(likelyOf[r], stripe)
                    if (likelyOf[r] > stripe) over += r
                }
                val overAt = IntArray(over.size)
                var all = packed
                over.forEachIndexed { k, r ->
                    overAt[k] = all
                    all += likelyOf[r]
                }
                val after = packedAt + over.toIntArray() + overAt
                val afterBytes = after.size.toLong() * Int.SIZE_BYTES
                val stagingAfter = pinned("after", afterBytes)
                stagingAfter.put(0, after)
                val afterRoom = room("after", afterBytes)
                afterRoom.upload(stagingAfter, afterBytes)
                val likely = room("likely", 2L * all * Int.SIZE_BYTES)
                val places = likely.at(0)
                val scores = likely.at(all.toLong() * Int.SIZE_BYTES)
                if (packed > 0) kernels.launch("shown_pack", count.toLong() * stripe, count, stripe, out(0), afterRoom, stripePlace, stripeScore, places, scores)
                if (over.isNotEmpty()) kernels.launch(
                    "shown_likely", over.size.toLong() * CudaKernels.BLOCK, *args,
                    afterRoom.at(count.toLong() * Int.SIZE_BYTES), afterRoom.at((count.toLong() + over.size) * Int.SIZE_BYTES), places, scores,
                )
                val got = pinned("likely", 2L * all * Int.SIZE_BYTES)
                likely.download(got, 2L * all * Int.SIZE_BYTES)
                val placesOf = got.ints(0, all)
                val scoresOf = got.floats(all.toLong() * Int.SIZE_BYTES, all)
                val overOf = HashMap<Int, Int>().also { m -> over.forEachIndexed { k, r -> m[r] = overAt[k] } }
                val result = List(count) { r ->
                    val many = likelyOf[r]
                    val from = overOf[r] ?: packedAt[r]
                    val others = minOf(takenOf[r], (least - many).coerceAtLeast(0))
                    val p = IntArray(many + others)
                    val s = FloatArray(many + others)
                    placesOf.copyInto(p, 0, from, from + many)
                    scoresOf.copyInto(s, 0, from, from + many)
                    tops.copyInto(p, many, r * l, r * l + others)
                    topScores.copyInto(s, many, r * l, r * l + others)
                    Shown(p, s)
                }
                val taken = System.nanoTime()
                if (count >= LOGGED) log.info(
                    "what {} items of {} values are shown with, chosen on the card: laid out and sent in {} ms, chosen and brought back in {} ms, " +
                        "the likely packed — {} rows of more than {} written again — and brought back in {} ms",
                    count, m, (sent - started) / 1_000_000, (chosen - sent) / 1_000_000, over.size, stripe, (taken - chosen) / 1_000_000,
                )
                return result
            }

            override fun close() {
                if (closed) return
                closed = true
                made.forEach { it.close() }
                rooms.values.forEach { it.close() }
                pins.values.forEach { it.close() }
            }
        }
    }

    /** No room on the card for what a scoring needs there: it is the main memory's. */
    private class NoRoom : RuntimeException("no room on the card")

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
