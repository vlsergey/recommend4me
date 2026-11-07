package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels

/**
 * THE [a]-TH SITE'S WORKS AGAINST THE [b]-TH'S ON THE CARD ([CardSearch]): of every work of the first, its values and
 * its authors with their kin as lists of the second's works ([RowLists]), the works the user answered on; and the
 * planes of a block of rows against every work of the second — the products of their titles ([t]) and descriptions
 * ([d]), the facets they share ([v], a plane a facet), whether they are of one author ([s]) — and every model's
 * likeliest work of every row ([best]). Freed with its [CardRoom].
 */
internal class Facing(
    private val search: PairSearch,
    private val a: Int,
    private val b: Int,
    private val x: SiteOnCard,
    private val y: SiteOnCard,
    private val models: Int,
    private val weights: Cublas.DeviceBytes,
    room: CardRoom,
) {
    private val own = search.sites[a]
    private val n = search.sites[b].count
    private val width = own.width
    private val families = search.families.size

    // The row's values, of each the other's works of it; its authors and their kin by their places among the other's
    private val values = RowLists.of(own.values.asList()) { v -> y.inverted.at[v + 1] - y.inverted.at[v] }
    private val kin =
        RowLists.of(own.kin.map { k -> k.asList().mapNotNull { y.inverted.authors[it] }.toIntArray() }) { k ->
            y.inverted.authorAt[k + 1] - y.inverted.authorAt[k]
        }

    // The works answered on, passed over: of every row, a list
    private val passed = RowLists.of(List(own.count) { search.answered[a to b]?.get(it) ?: IntArray(0) }) { 0 }

    private val rowAt = room.ints(values.at)
    private val rowValues = room.ints(values.entries)
    private val rowWeights =
        room.floatsOf(
            FloatArray(values.entries.size).also { flat ->
                own.weights.forEachIndexed { i, w -> w.copyInto(flat, values.at[i]) }
            }
        )
    private val cum = room.ints(values.cum)
    private val total = room.ints(values.total)
    private val kinAt = room.ints(kin.at)
    private val kinEntries = room.ints(kin.entries)
    private val kinCum = room.ints(kin.cum)
    private val kinTotal = room.ints(kin.total)
    private val passAt = room.ints(passed.at)
    private val pass = room.ints(passed.entries)
    private val familyOf = room.ints(search.familyOf)

    /** The rows of a block: as many as the planes of [ROOM] hold. */
    private val rows =
        (ROOM / maxOf(1L, n.toLong() * (Float.SIZE_BYTES * (2 + families) + 1)))
            .toInt()
            .coerceIn(1, maxOf(1, own.count))
    private val t = room.floats(rows.toLong() * n)
    private val d = room.floats(rows.toLong() * n)
    private val v = room.floats(families.toLong() * rows * n)
    private val s = room.bytes(rows.toLong() * n)
    private val best = room.bytes(rows.toLong() * models * Int.SIZE_BYTES)
    private val bestBack = room.pinned(rows.toLong() * models * Int.SIZE_BYTES)

    /** Of every work of the first site, the likeliest of the second by every model. */
    fun pairs(kernels: CudaKernels, card: Cublas): List<FoundPair> {
        val out = ArrayList<FoundPair>()
        var from = 0
        while (from < own.count) {
            val m = minOf(rows, own.count - from)
            val chosen = block(from, m, kernels, card)
            for (r in 0 until m) out +=
                ProcessorSearch.found(a, from + r, b, chosen.copyOfRange(r * models, (r + 1) * models))
            from += m
        }
        return out
    }

    /** The [m] rows from [from] on against every work of the second site: of every row, every model's likeliest. */
    private fun block(from: Int, m: Int, kernels: CudaKernels, card: Cublas): IntArray {
        products(from, m, card)
        // A block of the kernel's threads a row
        val threads = m.toLong() * CudaKernels.BLOCK
        shared(from, m, threads, kernels)
        return chosen(from, m, threads, kernels)
    }

    /**
     * The cosines of the titles and of the descriptions of the [m] rows from [from] on with every work of the second
     * site; the planes of the facets and the authors made nought.
     */
    private fun products(from: Int, m: Int, card: Cublas) {
        card.gemm(false, true, m, n, width, x.titles, from.toLong() * width, width, y.titles, 0, width, t, 0, n)
        card.gemm(
            false,
            true,
            m,
            n,
            width,
            x.descriptions,
            from.toLong() * width,
            width,
            y.descriptions,
            0,
            width,
            d,
            0,
            n,
        )
        v.zero(0, families.toLong() * m * n)
        s.zero()
    }

    /**
     * What the [m] rows from [from] on share with every work of the second site: their facets' cosines, their authors.
     */
    private fun shared(from: Int, m: Int, threads: Long, kernels: CudaKernels) {
        kernels.launch(
            "facets_onto",
            threads,
            m,
            from,
            rowAt,
            rowValues,
            rowWeights,
            cum,
            total,
            y.at,
            y.items,
            y.weights,
            familyOf,
            n,
            v,
        )
        kernels.launch(
            "authors_onto",
            threads,
            m,
            from,
            kinAt,
            kinEntries,
            kinCum,
            kinTotal,
            y.authorAt,
            y.authorItems,
            n,
            s,
        )
    }

    /** Every model's score of every pair of the [m] rows from [from] on, of every row every model's likeliest work. */
    private fun chosen(from: Int, m: Int, threads: Long, kernels: CudaKernels): IntArray {
        kernels.launch(
            "best",
            threads,
            m,
            from,
            n,
            own.count,
            t,
            d,
            v,
            s,
            x.described,
            y.described,
            x.facets,
            y.facets,
            search.sizes.size,
            x.sizes,
            y.sizes,
            passAt,
            pass,
            models,
            weights,
            families,
            best,
        )
        best.download(bestBack, m.toLong() * models * Int.SIZE_BYTES)
        return bestBack.ints(0, m * models)
    }

    private companion object {
        /** The memory a block's planes take on the card: memory, not meaning. */
        const val ROOM = 768L shl 20
    }
}
