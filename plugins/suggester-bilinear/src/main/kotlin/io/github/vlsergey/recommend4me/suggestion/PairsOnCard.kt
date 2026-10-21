package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PairsOnCard::class.java)

/**
 * THE PAIRS AND THE NAMES OF A FITTING ON THE CARD ([Ensemble] with its items' features there): of
 * every model still searching, its scores of every item against every value S = U Vᵀ, one product;
 * the loss and the slope of every pair that weighs anything by a kernel of our own (`pairs.cu`);
 * dU = S V, dV = Sᵀ U and the biases' slopes by three more products. The names' groups by kernels
 * too: their names' and values' coordinates gathered side by side; a tile of a group a block — its
 * scores, losses and slopes, the values' slopes carried back at once; a name's slopes by a warp.
 * What the processor works out between its products in [Ensemble.evaluate], the
 * same to the float's last digits but for the order of the sums: tens of millions of pairs and
 * names a model, a step, in milliseconds.
 *
 * The task's pairs and names put on the card once for the fitting; a step sends the values' and
 * the names' coordinates and the biases there, and takes back the values', the biases' and the
 * names' slopes — the items' coordinates and their slopes never leave it.
 */
internal class PairsOnCard private constructor(
    private val p: Problem,
    private val cublas: Cublas,
    private val kernels: CudaKernels,
    private val count: Int,
    private val r: Int,
    private val made: MutableList<AutoCloseable>,
) : AutoCloseable {
    private val n = p.n
    private val m = p.m
    private val h = p.hints.size

    private fun floats(count: Long) = (cublas.allocate(count) ?: throw NoRoom()).also { made += it }
    private fun bytes(count: Long) = (cublas.allocateBytes(count) ?: throw NoRoom()).also { made += it }

    /** The card had no room for the task's pairs: they stay on the processor. */
    private class NoRoom : RuntimeException()

    // The scores of a model's every item against every value, and the slopes written over a copy of nought
    private val s = floats(n.toLong() * m)
    private val g = floats(n.toLong() * m)

    // Of every model still searching: its values' coordinates and biases sent, their slopes taken back
    private val vT = floats(count.toLong() * r * m)
    private val bias = floats(count.toLong() * m)
    private val gE = floats(count.toLong() * r * m)
    private val gBias = floats(count.toLong() * m)
    private val ones = floats(n.toLong()).also { o -> o.upload(Floats(n).also { f -> for (i in 0 until n) f[i] = 1f }, 0, 0, n) }

    /** Of every model, its four sums: the loss, the slopes of the common bias, of the example, of the mention. */
    private val sums = bytes(count.toLong() * SUMS * Double.SIZE_BYTES)

    // Every pair that weighs anything, an entry each: its item, value, weight, label, example, mention
    private val entries: Long = p.active.sumOf { it.size.toLong() }
    private val item = bytes(entries * Int.SIZE_BYTES)
    private val value = bytes(entries * Int.SIZE_BYTES)
    private val weight = bytes(entries * Float.SIZE_BYTES)
    private val label = bytes(entries * Float.SIZE_BYTES)
    private val exampleOf = bytes(entries * Float.SIZE_BYTES)
    private val mentionOf = bytes(entries * Float.SIZE_BYTES)
    private val foldOf = bytes(n.toLong() * Int.SIZE_BYTES).also { it.upload(p.foldOf) }

    // The names: their coordinates of every model and their slopes; a group's names against its values, one group after another
    private val groups = p.hintGroups
    private val namePairs: Long = p.hintPairs
    private val uh = if (h > 0) floats(count.toLong() * r * h) else null
    private val duh = if (h > 0) floats(count.toLong() * r * h) else null
    private val sn = if (h > 0) floats(namePairs) else null
    private val names = groups.sumOf { it.rows.size }
    private val columns = groups.sumOf { it.values.size }
    private val firstPair = if (h > 0) bytes((groups.size + 1L) * Long.SIZE_BYTES) else null
    private val firstRow = if (h > 0) bytes(groups.size.toLong() * Int.SIZE_BYTES) else null
    private val firstValue = if (h > 0) bytes(groups.size.toLong() * Int.SIZE_BYTES) else null
    private val width = if (h > 0) bytes(groups.size.toLong() * Int.SIZE_BYTES) else null
    private val height = if (h > 0) bytes(groups.size.toLong() * Int.SIZE_BYTES) else null
    private val rows = if (h > 0) bytes(names.toLong() * Int.SIZE_BYTES) else null
    private val positive = if (h > 0) bytes(names.toLong() * Int.SIZE_BYTES) else null
    private val groupOfRow = if (h > 0) bytes(names.toLong() * Int.SIZE_BYTES) else null
    private val values = if (h > 0) bytes(columns.toLong() * Int.SIZE_BYTES) else null

    /** The tiles of the groups a block of `names_tile` takes each: the group, its first name, its first value. */
    private val tileList: IntArray = if (h == 0) IntArray(0) else run {
        val list = ArrayList<Int>()
        groups.forEachIndexed { k, group ->
            for (row in 0 until group.rows.size step TILE_ROWS) for (col in 0 until group.values.size step CudaKernels.BLOCK) {
                list += k
                list += row
                list += col
            }
        }
        list.toIntArray()
    }
    private val tiles = if (h > 0) bytes(tileList.size.toLong() * Int.SIZE_BYTES) else null

    // A model's names' and values' coordinates gathered by the groups, a row of rank numbers each
    private val gu = if (h > 0) floats(names.toLong() * r) else null
    private val gv = if (h > 0) floats(columns.toLong() * r) else null

    init {
        val items = IntArray(entries.toInt())
        val vals = IntArray(entries.toInt())
        val weights = FloatArray(entries.toInt())
        val labels = FloatArray(entries.toInt())
        val examples = FloatArray(entries.toInt())
        val mentions = FloatArray(entries.toInt())
        var at = 0
        for (i in 0 until n) {
            val active = p.active[i]
            for (j in active.indices) {
                items[at + j] = i
                vals[at + j] = active[j]
                weights[at + j] = p.activeWeight[i][j]
                labels[at + j] = p.activeLabel[i][j]
            }
            p.activeExamples[i].forEach { examples[at + it] = 1f }
            p.activeNamed[i].forEachIndexed { q, j -> mentions[at + j] = p.activeCounts[i][q] }
            at += active.size
        }
        item.upload(items)
        value.upload(vals)
        weight.upload(weights)
        label.upload(labels)
        exampleOf.upload(examples)
        mentionOf.upload(mentions)
        if (h > 0) {
            var pairAt = 0L
            var rowAt = 0
            var valueAt = 0
            val pairStarts = LongArray(groups.size + 1)
            val rowStarts = IntArray(groups.size)
            val valueStarts = IntArray(groups.size)
            val rowsOf = IntArray(names)
            val positives = IntArray(names)
            val rowGroups = IntArray(names)
            val valuesOf = IntArray(columns)
            groups.forEachIndexed { k, group ->
                pairStarts[k] = pairAt
                rowStarts[k] = rowAt
                valueStarts[k] = valueAt
                group.rows.forEachIndexed { q, row ->
                    rowsOf[rowAt + q] = row
                    positives[rowAt + q] = group.positive[q]
                    rowGroups[rowAt + q] = k
                }
                group.values.forEachIndexed { q, v -> valuesOf[valueAt + q] = v }
                pairAt += group.rows.size.toLong() * group.values.size
                rowAt += group.rows.size
                valueAt += group.values.size
            }
            pairStarts[groups.size] = pairAt
            firstPair!!.upload(pairStarts)
            firstRow!!.upload(rowStarts)
            firstValue!!.upload(valueStarts)
            width!!.upload(IntArray(groups.size) { groups[it].values.size })
            height!!.upload(IntArray(groups.size) { groups[it].rows.size })
            rows!!.upload(rowsOf)
            positive!!.upload(positives)
            groupOfRow!!.upload(rowGroups)
            values!!.upload(valuesOf)
            tiles!!.upload(tileList)
        }
    }

    /**
     * The pairs and the names of the first [k] slots, the model in each by [slotModel]: as
     * [Ensemble.pairs] and [Ensemble.names] of each. Read: the items' coordinates [uT] on the card,
     * the values' [vTHost], the biases [biasHost] and the names' coordinates [uhTHost] in the main
     * memory, the pair's own weights [pair]. Written: the items' slopes [duT] on the card; the
     * values' [gEHost], the biases' [gBiasHost], the names' [duhTHost] and the pair's own [gPair] in
     * the main memory; every model's loss, summed and not scaled, into [losses].
     */
    fun evaluate(
        k: Int, slotModel: IntArray, heldOut: IntArray, totals: FloatArray, named: Float,
        uT: Cublas.DeviceFloats, duT: Cublas.DeviceFloats, pair: Floats,
        vTHost: Floats, biasHost: Floats, uhTHost: Floats, gEHost: Floats, gBiasHost: Floats, duhTHost: Floats, gPair: Floats, losses: FloatArray,
    ) {
        val kr = k * r
        vT.upload(vTHost, 0, 0, kr * m)
        bias.upload(biasHost, 0, 0, k * m)
        if (h > 0) {
            uh!!.upload(uhTHost, 0, 0, kr * h)
            duh!!.zero(0, kr.toLong() * h)
        }
        sums.zero()
        for (slot in 0 until k) {
            val model = slotModel[slot]
            val scale = 1 / totals[model]
            val pairAt = slot * Bilinear.PAIR
            val example = pair[pairAt]
            val mention = pair[pairAt + 1]
            val common = pair[pairAt + 2]
            val uAt = slot.toLong() * r * n
            val vAt = slot.toLong() * r * m
            val biasAt = slot.toLong() * m
            val sumsAt = sums.at(slot.toLong() * SUMS * Double.SIZE_BYTES)
            // S = U Vᵀ: Uᵀ's columns against Vᵀ
            cublas.gemm(true, false, n, m, r, uT, uAt, n, vT, vAt, m, s, 0, m)
            g.zero(0, n.toLong() * m)
            kernels.launch(
                "pairs", entries, entries, m, s, g, item, value, weight, label, exampleOf, mentionOf,
                foldOf, heldOut[model], bias.at(biasAt), common, example, mention, scale, sumsAt,
            )
            // dUᵀ = Vᵀ Gᵀ, dVᵀ = Uᵀ G, the biases' slopes 1ᵀ G
            cublas.gemm(false, true, r, n, m, vT, vAt, m, g, 0, m, duT, uAt, n)
            cublas.gemm(false, false, r, m, n, uT, uAt, n, g, 0, m, gE, vAt, m)
            cublas.gemm(false, false, 1, m, n, ones, 0, n, g, 0, m, gBias, biasAt, m)
            if (h > 0) {
                val rows = rows!!
                val values = values!!
                val gu = gu!!
                val gv = gv!!
                val sn = sn!!
                val firstPair = firstPair!!
                val firstRow = firstRow!!
                val firstValue = firstValue!!
                val width = width!!
                kernels.launch("gather", names.toLong() * r, names, rows, uh!!.at(slot.toLong() * r * h), h, r, gu)
                kernels.launch("gather", columns.toLong() * r, columns, values, vT.at(vAt), m, r, gv)
                // The rank of the application's fittings compiled as known: its numbers in registers
                kernels.launch(
                    if (r == 16) "names_tile_16" else "names_tile", tileList.size / 3L * CudaKernels.BLOCK,
                    tiles!!, firstPair, firstRow, firstValue, width, height!!, values, positive!!, gu, gv, m, r,
                    bias.at(biasAt), common, mention, named, scale, sn, gE.at(vAt), gBias.at(biasAt), sumsAt,
                )
                // A warp a name
                kernels.launch(
                    if (r == 16) "names_rows_16" else "names_rows", names.toLong() * WARP,
                    names, groupOfRow!!, firstPair, firstRow, firstValue, width, rows, sn, gv, h, r, duh!!.at(slot.toLong() * r * h),
                )
            }
        }
        gE.download(gEHost, 0, 0, kr * m)
        gBias.download(gBiasHost, 0, 0, k * m)
        if (h > 0) duh!!.download(duhTHost, 0, 0, kr * h)
        val got = sums.doubles(k * SUMS)
        for (slot in 0 until k) {
            val at = slot * SUMS
            losses[slot] = got[at].toFloat()
            gPair[slot * Bilinear.PAIR] = got[at + 2].toFloat()
            gPair[slot * Bilinear.PAIR + 1] = got[at + 3].toFloat()
            gPair[slot * Bilinear.PAIR + 2] = got[at + 1].toFloat()
        }
    }

    override fun close() = made.forEach { it.close() }

    companion object {
        private const val SUMS = 4

        /** The threads of a warp, which `names_rows` gives a name. */
        private const val WARP = 32

        /** The rank a kernel's sums are made for (`MAX_RANK` of `pairs.cu`). */
        private const val MAX_RANK = 64

        private val NAMES = listOf("pairs", "gather", "names_tile", "names_tile_16", "names_rows", "names_rows_16")

        /** The names of a group a block of `names_tile` takes (`TILE_ROWS` of `pairs.cu`). */
        private const val TILE_ROWS = 32

        // The kernels compiled for the card of the process, once: the code, not anything of the data
        private var compiledFor: Cublas? = null
        private var compiled: CudaKernels? = null

        @Synchronized
        private fun kernels(cublas: Cublas): CudaKernels? {
            if (compiledFor !== cublas) {
                compiledFor = cublas
                val source = PairsOnCard::class.java.getResource("pairs.cu")!!.readText()
                compiled = cublas.kernels(source, NAMES)
            }
            return compiled
        }

        /**
         * The pairs and the names of [p] on the card of [cublas], for [count] models of [rank]; null
         * when they cannot be — the kernels not made, no room — the processor's then.
         */
        fun open(p: Problem, cublas: Cublas, count: Int, rank: Int): PairsOnCard? {
            if (rank > MAX_RANK) return null
            val kernels = kernels(cublas) ?: return null
            val made = ArrayList<AutoCloseable>()
            return try {
                PairsOnCard(p, cublas, kernels, count, rank, made)
            } catch (e: NoRoom) {
                made.forEach { it.close() }
                log.info("no room on the card for the pairs of {} items × {} values and {} names: they stay on the processor", p.n, p.m, p.hints.size)
                null
            }
        }
    }
}
