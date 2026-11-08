package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PairsOnCard::class.java)

/**
 * THE PAIRS AND THE NAMES OF A FITTING ON THE CARD ([Ensemble] with its items' features there), OF EVERY MODEL STILL
 * SEARCHING AT ONCE: a launch of a kernel of our own (`pairs.cu`) for them all, not one a model. The pairs of the items
 * and their values from both sides — a thread an item, its loss and slopes dU; a thread a value, its slopes dV and of
 * its bias — every pair's score U·V made again where it is needed, of every value an item merely lacks by what it may
 * have ([SuggestionPairs]), of its own pairs one by one: no matrix of the scores written to the card's memory and read
 * back, which a model's 93 million pairs made the most of a step. The names' groups the same: their names' and values'
 * coordinates gathered side by side; a tile of a group a block — its losses and the values' slopes; a name's slopes by
 * a warp, made again of the same numbers. What the processor works out between its products in [Ensemble.evaluate], the
 * same to the float's last digits but for the order of the sums.
 *
 * The task's pairs and names put on the card once for the fitting; a step sends the values' and the names' coordinates
 * and the biases there, and takes back the values', the biases' and the names' slopes — the items' coordinates and
 * their slopes never leave it.
 */
internal class PairsOnCard
private constructor(
    private val p: Problem,
    private val cublas: Cublas,
    private val kernels: CudaKernels,
    count: Int,
    private val r: Int,
    private val room: CardRoom,
) : AutoCloseable {
    private val n = p.n
    private val m = p.m
    private val h = p.hints.size

    /** The values' coordinates made here of the weights sent, and their slopes: written by the pairs and the names. */
    private val values = ValuesOnCard(p, cublas, room, count, r)
    private val vT = values.vT
    private val gE = values.gE

    // Of every model still searching: its biases sent, their slopes taken back; what it is read with — its scale, the
    // weight of the example, the common bias — and its held-out fold
    private val bias = room.floats(count.toLong() * m)
    private val gBias = room.floats(count.toLong() * m)
    private val params = room.floats(count.toLong() * PARAMS)
    private val heldOutOf = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val heldOutHost = IntArray(count)

    // The pair's own weights of every slot and their slopes; of a slot its scale and its penalty sent, its penalty's
    // sum and its loss made
    private val pair = room.floats(count.toLong() * Bilinear.PAIR)
    private val gPair = room.floats(count.toLong() * Bilinear.PAIR)
    private val scales = room.floats(count.toLong())
    private val lambdas = room.floats(count.toLong())
    private val penalties = room.bytes(count.toLong() * Double.SIZE_BYTES)
    private val lossesOnCard = room.floats(count.toLong())
    private val scalesHost = Floats(count)
    private val lambdasHost = Floats(count)
    private val lossesHost = Floats(count)

    /** Of every model, its three sums: the loss, the slopes of the common bias and of the example. */
    private val sums = room.bytes(count.toLong() * SUMS * Double.SIZE_BYTES)

    // Of every item, the weight of a value it merely lacks, and which of the arrays of values it may have is its
    // own, as a mask of a bit a value (-1: any) — every array once, however many items share it
    private val lack = room.floatsOf(p.pairs.lack)
    private val groupOf =
        IdentityHashMap<IntArray, Int>().also { made ->
            p.pairs.may.forEach { a -> if (a != null) made.putIfAbsent(a, made.size) }
        }
    private val group = room.ints(IntArray(n) { i -> p.pairs.may[i]?.let(groupOf::getValue) ?: -1 })
    private val words = (m + Int.SIZE_BITS - 1) / Int.SIZE_BITS
    private val masks = room.ints(masksOf(groupOf, words))
    private val foldOf = room.ints(p.foldOf)

    /** The items in the order of the arrays of values they may have: the threads of a warp alike ([pairs]). */
    private val order =
        room.ints((0 until n).sortedBy { i -> p.pairs.may[i]?.let(groupOf::getValue) ?: -1 }.toIntArray())

    /**
     * Every pair of an item not merely lacked, an entry each — one it has, one the user rejected, a draft, one barred
     * (weighing nought), one the site gives as an example — twice: by the items, the values of each ascending, for the
     * items' side; by the values, the items of each ascending, for the values' side. Where each item's and each value's
     * begin.
     */
    private val entries = Entries.of(p)
    private val firstEntry = room.longs(entries.firstOfItem)
    private val entryValue = room.ints(entries.value)
    private val entryWeight = room.floatsOf(entries.weight)
    private val entryLabel = room.floatsOf(entries.label)
    private val entryExample = room.floatsOf(entries.example)
    private val firstOfValue = room.longs(entries.firstOfValue)
    private val valueItem = room.ints(entries.byValueItem)
    private val valueWeight = room.floatsOf(entries.byValueWeight)
    private val valueLabel = room.floatsOf(entries.byValueLabel)
    private val valueExample = room.floatsOf(entries.byValueExample)

    /**
     * The pairs that weigh anything listed, when they are few of the items × the values ([ListedPairs]): then they
     * alone are gone through, not every pair of the dense sweep; null — the sweep.
     */
    private val listed = ListedPairs.of(p)?.let { ListedOnCard(it, room) }

    /** [ListedPairs] on the card. */
    private class ListedOnCard(list: ListedPairs, room: CardRoom) {
        val firstOfItem = room.longs(list.firstOfItem)
        val value = room.ints(list.value)
        val weight = room.floatsOf(list.weight)
        val label = room.floatsOf(list.label)
        val example = room.floatsOf(list.example)
        val firstOfValue = room.longs(list.firstOfValue)
        val item = room.ints(list.item)
        val byValueWeight = room.floatsOf(list.byValueWeight)
        val byValueLabel = room.floatsOf(list.byValueLabel)
        val byValueExample = room.floatsOf(list.byValueExample)
    }

    // The names: their coordinates of every model and their slopes; of every model its names' and values' coordinates
    // gathered by the groups, a row of rank numbers each
    private val groups = p.hintGroups
    private val uh = if (h > 0) room.floats(count.toLong() * r * h) else null
    private val duh = if (h > 0) room.floats(count.toLong() * r * h) else null
    private val names = groups.sumOf { it.rows.size }
    private val columns = groups.sumOf { it.values.size }
    private val gu = if (h > 0) room.floats(count.toLong() * names * r) else null
    private val gv = if (h > 0) room.floats(count.toLong() * columns * r) else null

    // The tiles of the groups a block takes, of each side: the group and its first value, or its first name
    private val valueTiles = tilesOf(groups) { it.values.size }
    private val nameTiles = tilesOf(groups) { it.rows.size }
    private val valueTileCount = valueTiles.size / TILE_NUMBERS
    private val nameTileCount = nameTiles.size / TILE_NUMBERS
    private val valueTilesOnCard = if (h > 0) room.ints(valueTiles) else null
    private val nameTilesOnCard = if (h > 0) room.ints(nameTiles) else null
    private val firstRow = if (h > 0) room.ints(starts(groups.map { it.rows.size })) else null
    private val firstValue = if (h > 0) room.ints(starts(groups.map { it.values.size })) else null
    private val width = if (h > 0) room.ints(IntArray(groups.size) { groups[it].values.size }) else null
    private val height = if (h > 0) room.ints(IntArray(groups.size) { groups[it].rows.size }) else null
    private val rows = if (h > 0) room.ints(groups.flatMap { it.rows.asList() }.toIntArray()) else null
    private val positive = if (h > 0) room.ints(groups.flatMap { it.positive.asList() }.toIntArray()) else null
    private val groupValues = if (h > 0) room.ints(groups.flatMap { it.values.asList() }.toIntArray()) else null

    /** The names' coordinates made here of the weights A here, and their slopes carried back to A here. */
    private val texts = if (h > 0) NameTextsOnCard(p, cublas, kernels, room, count, r, uh!!, duh!!) else null

    /**
     * The point and the gradient of every slot here ([SlotsOnCard]): the weights A and their slopes of the items'
     * features ([aT], [gA]), B and E of the values', the biases and the pair's own weights of the pairs.
     */
    fun slots(aT: Cublas.DeviceFloats, gA: Cublas.DeviceFloats) =
        SlotsOnCard(
            listOf(aT, values.bT, values.eT, bias, pair),
            listOf(gA, values.gB, gE, gBias, gPair),
            intArrayOf(r * p.design.itemWidth, r * p.design.valueWidth, r * m, m, Bilinear.PAIR),
        )

    /**
     * The values' and, when there are names, the names' coordinates of the first [k] models, of their weights here —
     * the names' of A ([aT]).
     */
    fun coordinates(k: Int, aT: Cublas.DeviceFloats) {
        values.coordinates(k)
        texts?.coordinates(k * r, aT)
    }

    /**
     * The slopes of B of the first [k] models, and, when there are names, theirs carried onto the slopes of A here
     * ([gA]) — once the items' features have written theirs there.
     */
    fun gradient(k: Int, gA: Cublas.DeviceFloats) {
        values.gradient(k)
        texts?.gradient(k * r, gA)
    }

    /**
     * The pairs and the names of the first [k] slots, the model in each by [slotModel] — scaled by one over its
     * training's weight ([totals]), its held-out fold of [heldOut]: as [Ensemble]'s on the processor, every number here
     * — the items' coordinates [uT] and their slopes [duT], the values' and the names' coordinates made before
     * ([coordinates]), the biases and the pair's own weights of the slots ([slots]). Their losses and slopes summed,
     * kept here till [finish].
     */
    fun evaluate(
        k: Int,
        slotModel: IntArray,
        heldOut: IntArray,
        totals: FloatArray,
        uT: Cublas.DeviceFloats,
        duT: Cublas.DeviceFloats,
    ) {
        for (slot in 0 until k) {
            scalesHost[slot] = 1 / totals[slotModel[slot]]
            heldOutHost[slot] = heldOut[slotModel[slot]]
        }
        scales.upload(scalesHost, 0, 0, k)
        heldOutOf.upload(heldOutHost)
        kernels.launch("params_of", k.toLong(), k, scales, pair, Bilinear.PAIR, params)
        sums.zero()
        pairs(k, uT, duT)
        if (h > 0) {
            duh!!.zero(0, k.toLong() * r * h)
            namesOf(k)
        }
    }

    /**
     * The losses of the first [k] slots finished, the model in each by [slotModel], into [losses]: the pair's own
     * slopes of the sums, the penalty of every weight of the [slots] but the common bias — its weight of [lambdas] —
     * with its slope, and the loss over the training's weight with its penalty.
     */
    fun finish(k: Int, slotModel: IntArray, lambdasOf: FloatArray, slots: SlotsOnCard, losses: FloatArray) {
        for (slot in 0 until k) lambdasHost[slot] = lambdasOf[slotModel[slot]]
        lambdas.upload(lambdasHost, 0, 0, k)
        kernels.launch("pair_slopes", k.toLong(), k, sums, Bilinear.PAIR, gPair)
        penalties.zero()
        // The pieces of the point, then of the gradient, then their sizes, as the kernel reads them
        val pieces = slots.point + slots.gradient + slots.sizes.toList()
        kernels.launch(
            "penalty",
            k.toLong() * PER_SLOT * CudaKernels.BLOCK,
            *(listOf(k, slots.size, lambdas) + pieces + penalties).toTypedArray(),
        )
        kernels.launch("losses_of", k.toLong(), k, sums, scales, lambdas, penalties, lossesOnCard)
        lossesOnCard.download(lossesHost, 0, 0, k)
        for (slot in 0 until k) losses[slot] = lossesHost[slot]
    }

    /**
     * The pairs of the first [k] models: their losses and slopes summed, the items' slopes into [duT] — a thread an
     * item; the values' and the biases' slopes into [gE] and [gBias] — a thread a value.
     */
    private fun pairs(k: Int, uT: Cublas.DeviceFloats, duT: Cublas.DeviceFloats) {
        val list = listed
        if (list != null) listedPairs(k, uT, duT, list) else sweptPairs(k, uT, duT)
    }

    /** [pairs] of the pairs that weigh anything [list]ed: they alone gone through. */
    private fun listedPairs(k: Int, uT: Cublas.DeviceFloats, duT: Cublas.DeviceFloats, list: ListedOnCard) {
        val known = r == KNOWN_RANK
        kernels.launch(
            if (known) "listed_items_16" else "listed_items",
            // A warp an item
            n.toLong() * k * WARP,
            *(listOf(k, n, m, r, uT, vT, bias, params, heldOutOf, foldOf) +
                    listOf(list.firstOfItem, list.value, list.weight, list.label, list.example, duT, sums))
                .toTypedArray(),
        )
        kernels.launch(
            if (known) "listed_values_16" else "listed_values",
            blocksOf(m) * k * CudaKernels.BLOCK,
            *(listOf(k, n, m, r, uT, vT, bias, params, heldOutOf, foldOf) +
                    listOf(list.firstOfValue, list.item, list.byValueWeight, list.byValueLabel) +
                    listOf(list.byValueExample, gE, gBias))
                .toTypedArray(),
        )
    }

    /** [pairs] of every pair of the items × the values swept, those that weigh nothing passed over. */
    private fun sweptPairs(k: Int, uT: Cublas.DeviceFloats, duT: Cublas.DeviceFloats) {
        val known = r == KNOWN_RANK
        kernels.launch(
            if (known) "pairs_items_16" else "pairs_items",
            blocksOf(n) * k * CudaKernels.BLOCK,
            k,
            n,
            m,
            r,
            uT,
            vT,
            bias,
            params,
            heldOutOf,
            lack,
            group,
            masks,
            words,
            foldOf,
            firstEntry,
            entryValue,
            entryWeight,
            entryLabel,
            entryExample,
            order,
            duT,
            sums,
        )
        kernels.launch(
            if (known) "pairs_values_16" else "pairs_values",
            blocksOf(m) * k * CudaKernels.BLOCK,
            k,
            n,
            m,
            r,
            uT,
            vT,
            bias,
            params,
            heldOutOf,
            lack,
            group,
            masks,
            words,
            foldOf,
            firstOfValue,
            valueItem,
            valueWeight,
            valueLabel,
            valueExample,
            gE,
            gBias,
        )
    }

    /**
     * The names of the first [k] models: their names' and values' coordinates gathered by the groups; from the values'
     * side the losses and the values' and the biases' slopes, from the names' side the names' slopes — a block a tile
     * of a group, the other side's coordinates a chunk at a time in its memory.
     */
    private fun namesOf(k: Int) {
        val known = r == KNOWN_RANK
        kernels.launch("gather", k.toLong() * names * r, k, names, rows!!, uh!!, h, r, gu!!)
        kernels.launch("gather", k.toLong() * columns * r, k, columns, groupValues!!, vT, m, r, gv!!)
        kernels.launch(
            if (known) "names_values_16" else "names_values",
            valueTileCount.toLong() * k * CudaKernels.BLOCK,
            k,
            valueTileCount,
            valueTilesOnCard!!,
            firstRow!!,
            firstValue!!,
            width!!,
            height!!,
            groupValues,
            positive!!,
            gu,
            gv,
            names,
            columns,
            m,
            r,
            bias,
            params,
            gE,
            gBias,
            sums,
        )
        kernels.launch(
            if (known) "names_items_16" else "names_items",
            nameTileCount.toLong() * k * CudaKernels.BLOCK,
            k,
            nameTileCount,
            nameTilesOnCard!!,
            firstRow,
            firstValue,
            width,
            height,
            rows,
            positive,
            groupValues,
            gu,
            gv,
            names,
            columns,
            bias,
            params,
            m,
            h,
            r,
            duh!!,
        )
    }

    override fun close() = room.close()

    /**
     * Every pair of an item not merely lacked ([PairsOnCard.entries]), by the items — [firstOfItem], [value], [weight],
     * [label], [example] — and by the values: [firstOfValue], [byValueItem] and the same numbers.
     */
    private class Entries(
        val firstOfItem: LongArray,
        val value: IntArray,
        val weight: FloatArray,
        val label: FloatArray,
        val example: FloatArray,
        val firstOfValue: LongArray,
        val byValueItem: IntArray,
        val byValueWeight: FloatArray,
        val byValueLabel: FloatArray,
        val byValueExample: FloatArray,
    ) {
        companion object {
            fun of(p: Problem): Entries {
                val own = Array(p.n) { ownEntries(p, it) }
                val first = LongArray(p.n + 1)
                for (i in 0 until p.n) first[i + 1] = first[i] + own[i].values.size
                val count = first[p.n].toInt()
                val value = IntArray(count)
                val weight = FloatArray(count)
                val label = FloatArray(count)
                val example = FloatArray(count)
                val ofValue = IntArray(p.m)
                for (i in 0 until p.n) {
                    val at = first[i].toInt()
                    val o = own[i]
                    o.values.copyInto(value, at)
                    o.weights.copyInto(weight, at)
                    o.labels.copyInto(label, at)
                    o.examples.copyInto(example, at)
                    o.values.forEach { ofValue[it]++ }
                }
                // The same entries by the values, the items of each in their order: counted, then laid out
                val firstOfValue = LongArray(p.m + 1)
                for (j in 0 until p.m) firstOfValue[j + 1] = firstOfValue[j] + ofValue[j]
                val next = IntArray(p.m) { firstOfValue[it].toInt() }
                val item = IntArray(count)
                val w = FloatArray(count)
                val l = FloatArray(count)
                val e = FloatArray(count)
                for (i in 0 until p.n) for (k in first[i].toInt() until first[i + 1].toInt()) {
                    val at = next[value[k]]++
                    item[at] = i
                    w[at] = weight[k]
                    l[at] = label[k]
                    e[at] = example[k]
                }
                return Entries(first, value, weight, label, example, firstOfValue, item, w, l, e)
            }

            /**
             * The item's own entries, its values ascending, with their weight, label and example: every pair it has,
             * the user rejected, a draft, one the site gives as an example; one that weighs nothing, with nothing to
             * take back of one lacked, none.
             */
            private fun ownEntries(p: Problem, i: Int): Own {
                val pairs = p.pairs
                val own = pairs.own[i]
                val examples = p.exampleValues[i]
                val values =
                    (own.asList() + examples.asList()).distinct().sorted().filter { v ->
                        val k = own.binarySearch(v)
                        (if (k >= 0) pairs.ownWeight[i][k] else pairs.lacking(i, v)) != 0f || pairs.lacking(i, v) != 0f
                    }
                val at = values.map { own.binarySearch(it) }
                return Own(
                    values.toIntArray(),
                    FloatArray(values.size) {
                        at[it].let { k -> if (k >= 0) pairs.ownWeight[i][k] else pairs.lacking(i, values[it]) }
                    },
                    FloatArray(values.size) { at[it].let { k -> if (k >= 0) pairs.ownLabel[i][k] else 0f } },
                    FloatArray(values.size) { if (examples.binarySearch(values[it]) >= 0) 1f else 0f },
                )
            }
        }
    }

    /** An item's own entries, its values ascending, with their weight, label and example. */
    private class Own(val values: IntArray, val weights: FloatArray, val labels: FloatArray, val examples: FloatArray)

    companion object {
        private const val SUMS = 3

        /** The threads of a warp, which `listed_items` gives an item. */
        private const val WARP = 32

        /** The numbers a model is read with: its scale, the weight of the example, the common bias. */
        private const val PARAMS = 3

        /** The blocks of a slot of `penalty` (`PER_SLOT` of `pairs.cu`). */
        private const val PER_SLOT = 64

        /** The rank of the application's fittings, which the `_16` kernels are compiled for. */
        private const val KNOWN_RANK = 16

        /** The numbers of a tile of a group: the group, its first value or name. */
        private const val TILE_NUMBERS = 2

        /** The rank a kernel's numbers are held for (`MAX_RANK` of `pairs.cu`). */
        private const val MAX_RANK = 64

        private val NAMES =
            listOf(
                "pairs_items",
                "pairs_items_16",
                "pairs_values",
                "pairs_values_16",
                "listed_items",
                "listed_items_16",
                "listed_values",
                "listed_values_16",
                "gather",
                "names_values",
                "names_values_16",
                "names_items",
                "names_items_16",
                "names_sum",
                "add_base",
                "names_back",
                "params_of",
                "penalty",
                "pair_slopes",
                "losses_of",
            )

        /** Where each of the [sizes] begins, one after another. */
        private fun starts(sizes: List<Int>): IntArray {
            var at = 0
            return IntArray(sizes.size) { k -> at.also { at += sizes[k] } }
        }

        /** The arrays of values the items may have ([groupOf], each its number) as masks of [words] a bit a value. */
        private fun masksOf(groupOf: Map<IntArray, Int>, words: Int): IntArray {
            val bits = IntArray(groupOf.size * words)
            groupOf.forEach { (list, k) ->
                list.forEach { v ->
                    val at = k * words + v / Int.SIZE_BITS
                    bits[at] = bits[at] or (1 shl (v % Int.SIZE_BITS))
                }
            }
            return bits
        }

        /**
         * The tiles of the [groups] a block takes each, of a side of [sizeOf] — its values or its names: the group, the
         * first of a tile of [CudaKernels.BLOCK] of them.
         */
        private fun tilesOf(groups: List<Problem.HintGroup>, sizeOf: (Problem.HintGroup) -> Int): IntArray {
            val list = ArrayList<Int>()
            groups.forEachIndexed { k, group ->
                for (first in 0 until sizeOf(group) step CudaKernels.BLOCK) {
                    list += k
                    list += first
                }
            }
            return list.toIntArray()
        }

        /** The blocks of [CudaKernels.BLOCK] threads that take [count] of something, a thread each. */
        private fun blocksOf(count: Int): Long = (count + CudaKernels.BLOCK - 1L) / CudaKernels.BLOCK

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
         * The pairs and the names of [p] on the card of [cublas], for [count] models of [rank]; null when they cannot
         * be — the kernels not made, no room — the processor's then.
         */
        fun open(p: Problem, cublas: Cublas, count: Int, rank: Int): PairsOnCard? {
            val kernels = if (rank > MAX_RANK) null else kernels(cublas)
            return kernels?.let { opened(p, cublas, it, count, rank) }
        }

        /** The pairs and the names of [p] put on the card by the [kernels]; null when the card has no room for them. */
        private fun opened(p: Problem, cublas: Cublas, kernels: CudaKernels, count: Int, rank: Int): PairsOnCard? {
            val room = CardRoom(cublas)
            return try {
                PairsOnCard(p, cublas, kernels, count, rank, room)
            } catch (e: CardRoom.NoRoom) {
                room.close()
                log.info(
                    "no room on the card for the pairs of {} items × {} values and {} names: they stay on the " +
                        "processor ({})",
                    p.n,
                    p.m,
                    p.hints.size,
                    e.message,
                )
                null
            }
        }
    }
}
