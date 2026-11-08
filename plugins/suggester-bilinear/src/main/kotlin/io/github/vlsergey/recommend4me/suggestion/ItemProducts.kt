package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import kotlin.concurrent.withLock

/**
 * THE TWO PRODUCTS OF THE ITEMS' FEATURES OF EVERY MODEL OF AN ENSEMBLE ([Ensemble]): the coordinates of every item by
 * every model, Uᵀ = Aᵀ Xᵀ, and the gradient back, dAᵀ = dUᵀ X — of the ensemble's weights [aT], coordinates [uT], their
 * slopes [duT] and the slopes of the weights [gA], of its [count] models of rank [r]. On the card when the launch asks
 * for it and it has room ([onCard]), else in the main memory.
 */
internal class ItemProducts(
    private val p: Problem,
    count: Int,
    private val r: Int,
    /** The card, when the launch asks for it: the items' features kept there and both their products made there. */
    card: Cublas?,
    private val aT: Floats,
    private val uT: Floats,
    private val duT: Floats,
    private val gA: Floats,
) : AutoCloseable {
    private val n = p.n
    private val dx = p.design.itemWidth

    /** Whether there are names, whose part of dA is added in the main memory. */
    private val named = p.hints.isNotEmpty()

    /**
     * THE ITEMS' VECTORS ON THE CARD, and the sides of their two products: every block there once for the fitting — as
     * the application put the task's there for its operation, or put there now — its rows of the items that have it
     * alone, read at the card's speed, not the main memory's. A step sends the models' A there and takes U back, sends
     * dU and takes dA back — tens of megabytes, not the features; the part of the ones ([ItemFeatures.ones]) made in
     * the main memory and sent with them. A block's product written into [part], carried onto the items' columns by a
     * kernel ([FeatureKernels]); its rows, kept in half precision, made single a run of [FeatureKernels.CARD_ROWS] at a
     * time into [rows] for the library to multiply. Null on the processor: no card, no kernels, or no room.
     */
    internal class OnCard(
        val cublas: Cublas,
        val kernels: CudaKernels,
        val blocks: List<CardRows>,
        val aT: Cublas.DeviceFloats,
        val uT: Cublas.DeviceFloats,
        val duT: Cublas.DeviceFloats,
        val gA: Cublas.DeviceFloats,
        val part: Cublas.DeviceFloats,
        /** The weights, a run of a block's rows and the slopes of a block's items in brain floating point. */
        val halves: HalvesOnCard,
        /** The items' ones there ([OnesOnCard]). */
        val ones: OnesOnCard,
        /** What was put there for the fitting: freed with it — the application's rows are the application's. */
        private val made: List<AutoCloseable>,
    ) : AutoCloseable {
        override fun close() = made.forEach { it.close() }
    }

    val onCard: OnCard? = card?.let { c ->
        FeatureKernels.of(c)?.let { kernels -> c.lock.withLock { opened(c, kernels, count) } }
    }

    /** A block's part of the products in the main memory, when they are made there. */
    private val blockPart: ItemFeatures.Scratch =
        if (onCard == null) p.x.scratchOf(count * r) else ItemFeatures.Scratch(Floats(1), Floats(1))

    /**
     * The items' vectors and the rooms of the products of [count] models on the card [c]; null — what was put there
     * freed — when it has no room for every one of them.
     */
    private fun opened(c: Cublas, kernels: CudaKernels, count: Int): OnCard? {
        val made = ArrayList<AutoCloseable>()
        fun room(size: Long) = c.allocate(size)?.also { made += it }
        val blocks = p.x.blocks.map { b -> b.card ?: CardRows.put(c, b.vectors, b.items)?.also { made += it } }
        val a = room(count.toLong() * r * dx)
        val u = room(count.toLong() * r * n)
        val du = room(count.toLong() * r * n)
        val g = room(count.toLong() * r * dx)
        val part = room(maxOf(1L, count.toLong() * r * p.x.widest))
        val halves = HalvesOnCard.of(c, count.toLong() * r * dx, p.x, count.toLong() * r, made)
        val ones = OnesOnCard.of(c, p.x, made)
        // Every block and every room on the card, or nothing kept there
        val missing = listOf(a, u, du, g, part).any { it == null } || halves == null || ones == null
        return if (blocks.any { it == null } || missing) {
            made.forEach { it.close() }
            null
        } else
            OnCard(
                c,
                kernels,
                blocks.map { it!! },
                a!!,
                u!!,
                du!!,
                g!!,
                part!!,
                halves!!,
                ones!!,
                made,
            )
    }

    override fun close() {
        onCard?.close()
    }

    /**
     * Uᵀ = Aᵀ Xᵀ of the first [kr] rows of Aᵀ — every model still searching: the vectors' part on the card when they
     * are there, onto the ones' part made here; Aᵀ sent there first unless the searches write it there ([aOnCard]) —
     * then brought over, the ones read it here.
     */
    fun coordinates(kr: Int, aOnCard: Boolean, keep: Boolean = false) {
        val c = onCard
        if (c == null) {
            p.x.transposedCoordinates(aT, kr, uT, blockPart)
            return
        }
        if (!aOnCard) c.aT.upload(aT, 0, 0, kr * dx)
        // The weights as brain floating point, for the tensor cores' products
        c.kernels.launch("floats_to_bf16", kr.toLong() * dx, kr.toLong() * dx, c.aT, c.halves.a)
        c.kernels.launch("ones_coordinates", kr.toLong() * n, kr, n, dx, c.ones.at, c.ones.features, c.aT, c.uT)
        // A block's rows multiplied a run at a time, then carried onto its items' columns
        p.x.blocks.forEachIndexed { k, b ->
            val rows = c.blocks[k]
            runs(c, rows) { from, many ->
                c.cublas.gemmHalves(
                    false,
                    true,
                    kr,
                    many,
                    b.dim,
                    c.halves.a,
                    b.column.toLong(),
                    dx,
                    c.halves.rows,
                    0,
                    b.dim,
                    c.part,
                    from.toLong(),
                    b.count,
                    alpha = b.scale,
                )
            }
            c.kernels.launch("scatter_columns", kr.toLong() * b.count, kr, b.count, c.part, rows.items, c.uT, n)
        }
        // Left there when the pairs are worked out there ([keep]): nothing of the processor reads them then
        if (!keep) c.uT.download(uT, 0, 0, kr * n)
    }

    /**
     * dAᵀ = dUᵀ X of the first [kr] rows: the vectors' part on the card when they are there, beside the ones' part made
     * here and sent; taken back unless the searches read it there ([aOnCard]) — then only for the names to add their
     * part — or unless the caller takes it back itself ([bringBack]): the names' part added there first.
     */
    fun gradient(kr: Int, aOnCard: Boolean, duOnCard: Boolean = false, bringBack: Boolean = !aOnCard || named) {
        val c = onCard
        if (c == null) {
            p.x.transposedGradient(duT, kr, gA, blockPart)
            return
        }
        // Written there by the pairs on the card ([duOnCard]), else sent
        if (!duOnCard) c.duT.upload(duT, 0, 0, kr * n)
        // The features no item has as a one nought, those of the ones' slopes summed: a thread or a warp each
        c.gA.zero(0, kr.toLong() * dx)
        val ones = c.ones
        listOf(ones.light to 1, ones.heavy to WARP).forEach { (columns, threads) ->
            c.kernels.launch(
                if (threads == 1) "ones_gradient_light" else "ones_gradient_heavy",
                kr.toLong() * columns.count * threads,
                kr,
                n,
                dx,
                columns.count,
                columns.list,
                ones.itemsAt,
                ones.items,
                c.duT,
                c.gA,
            )
        }
        // The columns of a block's items taken, then multiplied by its rows a run at a time — onto the block's
        // columns of dA, nought of the ones' part sent
        p.x.blocks.forEachIndexed { k, b ->
            val rows = c.blocks[k]
            c.kernels.launch(
                "gather_columns_bf16",
                kr.toLong() * b.count,
                kr,
                b.count,
                c.duT,
                n,
                rows.items,
                c.halves.part,
            )
            runs(c, rows) { from, many ->
                c.cublas.gemmHalves(
                    false,
                    false,
                    kr,
                    b.dim,
                    many,
                    c.halves.part,
                    from.toLong(),
                    b.count,
                    c.halves.rows,
                    0,
                    b.dim,
                    c.gA,
                    b.column.toLong(),
                    dx,
                    beta = 1f,
                    alpha = b.scale,
                )
            }
        }
        if (bringBack) c.gA.download(gA, 0, 0, kr * dx)
    }

    /**
     * Every run of [FeatureKernels.CARD_ROWS] rows of a block made brain floating point into the card's
     * [HalvesOnCard.rows]: [work] of its first row and its number.
     */
    private inline fun runs(c: OnCard, rows: CardRows, work: (Int, Int) -> Unit) {
        var from = 0
        while (from < rows.count) {
            val many = minOf(FeatureKernels.CARD_ROWS, rows.count - from)
            FeatureKernels.halves(c.kernels, rows, from, many, c.halves.rows)
            work(from, many)
            from += many
        }
    }

    private companion object {
        /** The threads of a warp, which `ones_gradient_heavy` gives a feature. */
        const val WARP = 32
    }
}

/**
 * THE ITEMS' ONES ON THE CARD ([ItemFeatures.ones]): the constant, the marks of their blocks, their values of the other
 * facets — by the items, where each item's begin ([at]) and its features ([features]), for the coordinates; by the
 * features, where each feature's begin ([itemsAt]) and its items ([items]), for the gradient back. Put there once for
 * the fitting: the ones of every model summed there, never the weights A brought over for them.
 */
internal class OnesOnCard(
    val at: Cublas.DeviceBytes,
    val features: Cublas.DeviceBytes,
    val itemsAt: Cublas.DeviceBytes,
    val items: Cublas.DeviceBytes,
    /**
     * The features some item has as a one: of few items ([light], a thread each) and of many ([heavy], a warp each).
     */
    val light: Columns,
    val heavy: Columns,
) {
    /** A list of features on the card, and how many. */
    class Columns(val list: Cublas.DeviceBytes, val count: Int)

    companion object {
        /** The items a feature has as a one from which a warp, not a thread, sums their slopes. */
        private const val HEAVY = 64

        /** The ones of [x] put on the card of [c], what was put there into [made]; null when it has no room. */
        fun of(c: Cublas, x: ItemFeatures, made: MutableList<AutoCloseable>): OnesOnCard? {
            fun ints(of: IntArray) =
                c.allocateBytes(maxOf(1L, of.size.toLong() * Int.SIZE_BYTES))?.also {
                    made += it
                    it.upload(of)
                }
            val (at, features) = x.onesFlat()
            val (itemsAt, items) = byFeature(x, at, features)
            val counted = (0 until x.width).filter { itemsAt[it + 1] > itemsAt[it] }
            val (heavy, light) = counted.partition { itemsAt[it + 1] - itemsAt[it] >= HEAVY }
            val put = listOf(ints(at), ints(features), ints(itemsAt), ints(items))
            val lists = listOf(ints(light.toIntArray()), ints(heavy.toIntArray()))
            return if (null in put || null in lists) null
            else
                OnesOnCard(
                    put[0]!!,
                    put[1]!!,
                    put[2]!!,
                    put.last()!!,
                    Columns(lists.first()!!, light.size),
                    Columns(lists.last()!!, heavy.size),
                )
        }

        /**
         * The ones by the features: of every feature of the layout, where its items begin — of [ItemFeatures.width] +
         * 1, the last their number — and its items in their order; of the ones by the items, [at] and [features].
         */
        private fun byFeature(x: ItemFeatures, at: IntArray, features: IntArray): Pair<IntArray, IntArray> {
            val width = x.width
            val first = IntArray(width + 1)
            features.forEach { first[it + 1]++ }
            for (c in 0 until width) first[c + 1] += first[c]
            val next = first.copyOf(width)
            val items = IntArray(features.size)
            for (i in 0 until x.rows) for (k in at[i] until at[i + 1]) items[next[features[k]]++] = i
            return first to items
        }
    }
}

/**
 * WHAT THE ITEMS' FEATURES' PRODUCTS READ ON THE TENSOR CORES ([ItemProducts.OnCard]), in brain floating point (16
 * bits: single precision's range, a fraction of 8 bits) — two bytes a number: the weights of every model ([a]), a run
 * of a block's rows ([rows]), the slopes of a block's items ([part]); the products summed and written in single
 * precision. Twice as fast as the single precision's products, which on this card are its cores' alone.
 */
internal class HalvesOnCard(val a: Cublas.DeviceBytes, val rows: Cublas.DeviceBytes, val part: Cublas.DeviceBytes) {
    companion object {
        private const val HALF = 2L

        /**
         * The rooms of [weights] numbers of the weights, of a run of the rows of [x] and of [kr] rows of its widest
         * block's items, on the card of [c], what was put there into [made]; null when it has no room.
         */
        fun of(c: Cublas, weights: Long, x: ItemFeatures, kr: Long, made: MutableList<AutoCloseable>): HalvesOnCard? {
            fun room(numbers: Long) = c.allocateBytes(maxOf(1L, numbers) * HALF)?.also { made += it }
            val a = room(weights)
            val rows = room(minOf(FeatureKernels.CARD_ROWS, x.widest).toLong() * x.longest)
            val part = room(kr * x.widest)
            return if (a == null || rows == null || part == null) null else HalvesOnCard(a, rows, part)
        }
    }
}
