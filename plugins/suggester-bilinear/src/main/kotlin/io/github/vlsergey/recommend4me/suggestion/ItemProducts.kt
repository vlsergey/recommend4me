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
        val rows: Cublas.DeviceFloats,
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
        val rows = room(maxOf(1L, minOf(FeatureKernels.CARD_ROWS, p.x.widest).toLong() * p.x.longest))
        // Every block and every room on the card, or nothing kept there
        return if (blocks.any { it == null } || listOf(a, u, du, g, part, rows).any { it == null }) {
            made.forEach { it.close() }
            null
        } else OnCard(c, kernels, blocks.map { it!! }, a!!, u!!, du!!, g!!, part!!, rows!!, made)
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
        if (aOnCard) c.aT.download(aT, 0, 0, kr * dx) else c.aT.upload(aT, 0, 0, kr * dx)
        p.x.onesCoordinates(aT, kr, uT)
        c.uT.upload(uT, 0, 0, kr * n)
        // A block's rows multiplied a run at a time, then carried onto its items' columns
        p.x.blocks.forEachIndexed { k, b ->
            val rows = c.blocks[k]
            runs(c, rows) { from, many ->
                c.cublas.gemm(
                    false,
                    true,
                    kr,
                    many,
                    b.dim,
                    c.aT,
                    b.column.toLong(),
                    dx,
                    c.rows,
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
     * part.
     */
    fun gradient(kr: Int, aOnCard: Boolean, duOnCard: Boolean = false) {
        val c = onCard
        if (c == null) {
            p.x.transposedGradient(duT, kr, gA, blockPart)
            return
        }
        // Written there by the pairs on the card ([duOnCard]) and brought over for the ones, else sent
        if (duOnCard) c.duT.download(duT, 0, 0, kr * n) else c.duT.upload(duT, 0, 0, kr * n)
        p.x.onesGradient(duT, kr, gA)
        c.gA.upload(gA, 0, 0, kr * dx)
        // The columns of a block's items taken, then multiplied by its rows a run at a time — onto the block's
        // columns of dA, nought of the ones' part sent
        p.x.blocks.forEachIndexed { k, b ->
            val rows = c.blocks[k]
            c.kernels.launch("gather_columns", kr.toLong() * b.count, kr, b.count, c.duT, n, rows.items, c.part)
            runs(c, rows) { from, many ->
                c.cublas.gemm(
                    false,
                    false,
                    kr,
                    b.dim,
                    many,
                    c.part,
                    from.toLong(),
                    b.count,
                    c.rows,
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
        if (!aOnCard || named) c.gA.download(gA, 0, 0, kr * dx)
    }

    /**
     * Every run of [FeatureKernels.CARD_ROWS] rows of a block made single precision into the card's [OnCard.rows]:
     * [work] of its first row and its number.
     */
    private inline fun runs(c: OnCard, rows: CardRows, work: (Int, Int) -> Unit) {
        var from = 0
        while (from < rows.count) {
            val many = minOf(FeatureKernels.CARD_ROWS, rows.count - from)
            FeatureKernels.floats(c.kernels, rows, from, many, c.rows)
            work(from, many)
            from += many
        }
    }
}
