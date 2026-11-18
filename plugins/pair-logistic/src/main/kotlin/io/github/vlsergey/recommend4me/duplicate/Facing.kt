package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Halves

/**
 * A RUN OF THE [Tile.a]-TH SITE'S WORKS AGAINST A RUN OF THE [Tile.b]-TH'S ON THE CARD ([CardSearch], [Tiles]): of
 * every work of the first, its values and its authors with their kin as lists of the second's works ([RowLists]), the
 * works the user answered on; and the planes of a block of rows against every work of the second — the products of
 * their titles ([t]) and descriptions ([d]), the facets they share ([v], a plane a facet), whether they are of one
 * author ([s]) — and every model's likeliest work of every row ([best]) with its score ([scores]); of one site, a work
 * never with itself. Freed with its [CardRoom].
 */
internal class Facing(
    private val tile: Tile,
    private val x: SiteOnCard,
    private val y: SiteOnCard,
    private val models: Int,
    private val weights: Cublas.DeviceBytes,
    room: CardRoom,
    /** The memory the planes of a block take on the card ([MOST_ROOM] at most): as many rows as it holds. */
    planes: Long,
) {
    private val search = tile.search
    private val own = tile.own
    private val n = tile.other.count
    private val width = own.width
    private val families = search.families.size

    // The row's values, of each the other's works of it; its authors and their kin by their places among the other's
    private val values = RowLists.of(own.values.asList()) { v -> y.inverted.at[v + 1] - y.inverted.at[v] }
    private val kin =
        RowLists.of(own.kin.map { k -> k.asList().mapNotNull { y.inverted.authors[it] }.toIntArray() }) { k ->
            y.inverted.authorAt[k + 1] - y.inverted.authorAt[k]
        }

    // The works answered on, passed over: of every row, a list
    private val passed = RowLists.of(tile.passed) { 0 }

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

    /** The rows of a block: as many as the [planes] hold. */
    private val rows =
        (planes / maxOf(1L, n.toLong() * (Float.SIZE_BYTES * (PLANES + families) + 1)))
            .toInt()
            .coerceIn(1, maxOf(1, own.count))
    private val t = room.floats(rows.toLong() * n)
    private val a = room.floats(rows.toLong() * n)
    private val d = room.floats(rows.toLong() * n)

    // The descriptions of the two compared: of one encoder's vectors
    private val descriptions = search.descriptionsCompared(tile.a, tile.b)
    private val described = own.descriptions.cols
    private val v = room.floats(families.toLong() * rows * n)
    private val s = room.bytes(rows.toLong() * n)
    private val best = room.bytes(rows.toLong() * models * Int.SIZE_BYTES)
    private val bestBack = room.pinned(rows.toLong() * models * Int.SIZE_BYTES)
    private val scores = room.bytes(rows.toLong() * models * Float.SIZE_BYTES)
    private val scoresBack = room.pinned(rows.toLong() * models * Float.SIZE_BYTES)

    /** Of every work of the first site, the likeliest of the second by every model offered to the [likeliest]. */
    fun offer(kernels: CudaKernels, card: Cublas, likeliest: Likeliest) {
        var from = 0
        while (from < own.count) {
            val m = minOf(rows, own.count - from)
            val chosen = block(from, m, kernels, card)
            val score = scoresBack.floats(0, m * models)
            for (r in 0 until m) for (k in 0 until models) {
                val at = r * models + k
                val j = chosen[at]
                likeliest.offer(
                    tile.a,
                    tile.rows.from + from + r,
                    k,
                    score[at].toDouble(),
                    tile.b,
                    if (j >= 0) tile.columns.from + j else -1,
                )
            }
            from += m
        }
    }

    /**
     * The [m] rows from [from] on against every work of the second site: of every row, every model's likeliest, its
     * score brought back with it.
     */
    private fun block(from: Int, m: Int, kernels: CudaKernels, card: Cublas): IntArray {
        products(from, m, card)
        // A block of the kernel's threads a row
        val threads = m.toLong() * CudaKernels.BLOCK
        shared(from, m, threads, kernels)
        return chosen(from, m, threads, kernels)
    }

    /**
     * The cosines of the trigrams of the titles and of the authors' names, and of the descriptions, of the [m] rows
     * from [from] on with every work of the second site; the planes of the facets and the authors made nought.
     */
    private fun products(from: Int, m: Int, card: Cublas) {
        // Of halves on the tensor cores, summed in single precision: the vectors as the database keeps them
        val halves = card.halves
        halves.gemm(
            false,
            true,
            m,
            n,
            width,
            x.titles,
            from.toLong() * width,
            width,
            y.titles,
            0,
            width,
            t,
            0,
            n,
            halves = Halves.IEEE,
        )
        halves.gemm(
            false,
            true,
            m,
            n,
            width,
            x.authorNames,
            from.toLong() * width,
            width,
            y.authorNames,
            0,
            width,
            a,
            0,
            n,
            halves = Halves.IEEE,
        )
        if (descriptions)
            halves.gemm(
                false,
                true,
                m,
                n,
                described,
                x.descriptions,
                from.toLong() * described,
                described,
                y.descriptions,
                0,
                described,
                d,
                0,
                n,
                halves = Halves.IEEE,
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
            a,
            x.titleLengths,
            y.titleLengths,
            x.authorLengths,
            y.authorLengths,
            if (descriptions) 1 else 0,
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
            if (tile.self) 1 else 0,
            best,
            scores,
        )
        best.download(bestBack, m.toLong() * models * Int.SIZE_BYTES)
        scores.download(scoresBack, m.toLong() * models * Float.SIZE_BYTES)
        return bestBack.ints(0, m * models)
    }

    companion object {
        /**
         * The memory a block's planes take on the card at most, halved while the card has no room for it ([NoRoom]):
         * every block reads every work of the other site's vectors again, and of a million works of 11 facets a row's
         * planes are 74 MB — 768 MB made blocks of ten rows, the products read the vectors over again a hundred
         * thousand times (2026-11-17).
         */
        const val MOST_ROOM = 4L shl 30

        /** The least memory of a block's planes tried before the search is given to the processor. */
        const val LEAST_ROOM = 64L shl 20

        /** The planes of a row's every pair besides the facets': the titles, the authors' names, the descriptions. */
        const val PLANES = 3
    }
}
