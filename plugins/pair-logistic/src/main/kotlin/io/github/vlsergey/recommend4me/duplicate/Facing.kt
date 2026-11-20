package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.NoCardRoom

/**
 * A RUN OF THE [Tile.a]-TH SITE'S WORKS AGAINST A RUN OF THE [Tile.b]-TH'S ON THE CARD ([CardSearch], [Tiles]): of
 * every work of the first, its authors with their kin as places among the second's authors, and the works the user
 * answered on; the planes of a block of rows against every work of the second — the products of their titles ([t]), of
 * their authors' names ([a]) and of their descriptions ([d]), in half precision, and what the rare values they share
 * add to every model's score ([p], a plane a model, not a facet: a model's score is a sum over its facets) — and every
 * model's likeliest work of every row ([best]) with its score ([scores]); of one site, a work never with itself. The
 * frequent values a pair shares ([Frequent]) and whether it is of one author are the kernel's of the scores, of the two
 * works' lists (user, 2026-11-18: "remove the zeroings and the planes"). Freed with its [CardRoom].
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

    /** How much of the search is done, and the time of its steps on the card. */
    private lateinit var compared: Compared
    private val own = tile.own
    private val n = tile.other.count
    private val width = own.width
    private val families = search.families.size

    // The row's authors and their kin by their places among the other's authors: those the other has
    private val kin = Lists.of(own.kin.map { k -> k.asList().mapNotNull { y.inverted.authors[it] }.toIntArray() })

    // The row's own authors so: one author with the other's, not one in part
    private val ownAuthors =
        Lists.of(own.authors.map { k -> k.asList().mapNotNull { y.inverted.authors[it] }.toIntArray() })

    // The works answered on, passed over: of every row, a list
    private val passed = Lists.of(tile.passed)

    // The row's rare values, of each the other's works of it
    private val rare = RowLists.of(x.rare.lists) { v -> y.inverted.at[v + 1] - y.inverted.at[v] }

    init {
        check(search.sizes.size <= MOST_SIZES) { "${search.sizes.size} numbers of size" }
        check((0 until own.count).all { kin.at[it + 1] - kin.at[it] <= MOST_KIN }) { "an author of more kin" }
        check(families <= MOST_FAMILIES) { "$families facets" }
    }

    private val cum = room.ints(rare.cum)
    private val total = room.ints(rare.total)
    private val kinAt = room.ints(kin.at)
    private val kinEntries = room.ints(kin.entries)
    private val ownAuthorAt = room.ints(ownAuthors.at)
    private val ownAuthorEntries = room.ints(ownAuthors.entries)
    private val passAt = room.ints(passed.at)
    private val pass = room.ints(passed.entries)
    private val familyOf = room.ints(search.familyOf)
    private val frequentFamily = room.ints(x.frequent.family)

    /** The rows of a block: as many as the [planes] hold, and the room the block was given has left. */
    private val rows =
        (minOf(planes, room.left * ROOM_SHARE / ROOM_PARTS) /
                maxOf(1L, n.toLong() * (HALF_BYTES * PLANES + Float.SIZE_BYTES * models)))
            .toInt()
            .coerceIn(1, maxOf(1, own.count))
    private val t = room.bytes(rows.toLong() * n * HALF_BYTES)
    private val a = room.bytes(rows.toLong() * n * HALF_BYTES)
    private val d = room.bytes(rows.toLong() * n * HALF_BYTES)
    private val tv = room.bytes(rows.toLong() * n * HALF_BYTES)
    private val dl = room.bytes(LETTERED.toLong() * rows * n * HALF_BYTES)
    private val p = room.floats(models.toLong() * rows * n)

    // The descriptions of the two compared, and their titles' vectors: of one encoder's
    private val descriptions = search.descriptionsCompared(tile.a, tile.b)
    private val described = own.descriptions.cols
    private val titleVectors = search.titleVectorsCompared(tile.a, tile.b)
    private val titleColumns = own.titleVectors.cols
    private val best = room.bytes(rows.toLong() * models * Int.SIZE_BYTES)
    private val bestBack = room.pinned(rows.toLong() * models * Int.SIZE_BYTES)
    private val scores = room.bytes(rows.toLong() * models * Float.SIZE_BYTES)
    private val scoresBack = room.pinned(rows.toLong() * models * Float.SIZE_BYTES)

    /** Of every work of the first site, the likeliest of the second by every model offered to the [likeliest]. */
    fun offer(kernels: CudaKernels, card: Cublas, likeliest: Likeliest, compared: Compared) {
        this.compared = compared
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
            compared.add(m.toLong() * n)
            from += m
        }
    }

    /**
     * The [m] rows from [from] on against every work of the second site: of every row, every model's likeliest, its
     * score brought back with it.
     */
    private fun block(from: Int, m: Int, kernels: CudaKernels, card: Cublas): IntArray {
        compared.timed(Phase.PRODUCTS, card) { products(from, m, card) }
        // A block of the kernel's threads a row; of the scores, a block of ROWS rows
        compared.timed(Phase.SHARED, card) { shared(from, m, m.toLong() * CudaKernels.BLOCK, kernels) }
        val scored = (m + ROWS - 1).toLong() / ROWS * CudaKernels.BLOCK
        return compared.timed(Phase.CHOSEN, card) { chosen(from, scored, m, kernels) }
    }

    /**
     * The cosines of the trigrams of the titles, of the authors' names and of the descriptions, and of the vectors of
     * the descriptions and of the titles, of the [m] rows from [from] on with every work of the second site: halves
     * summed in halves, the tensor cores' full speed.
     */
    private fun products(from: Int, m: Int, card: Cublas) {
        val halves = card.halves
        fun product(k: Int, own: Cublas.DeviceBytes, other: Cublas.DeviceBytes, into: Cublas.DeviceBytes) =
            halves.gemmToHalves(false, true, m, n, k, own, from.toLong() * k, k, other, 0, k, into, 0, n)
        product(width, x.titles, y.titles, t)
        product(width, x.authorNames, y.authorNames, a)
        if (descriptions) product(described, x.descriptions, y.descriptions, d)
        if (titleVectors) product(titleColumns, x.titleVectors, y.titleVectors, tv)
        // The texts' planes one after another, of the block's rows each
        for (k in 0 until LETTERED) halves.gemmToHalves(
            false,
            true,
            m,
            n,
            width,
            x.letters[k],
            from.toLong() * width,
            width,
            y.letters[k],
            0,
            width,
            dl,
            k.toLong() * m * n,
            n,
        )
    }

    /**
     * What the rare values the [m] rows from [from] on share with every work of the second site add to every model's
     * score: of every value of a row, the works of the second that have it.
     */
    private fun shared(from: Int, m: Int, threads: Long, kernels: CudaKernels) {
        p.zero(0, models.toLong() * m * n)
        kernels.launch(
            "facets_onto",
            threads,
            m,
            from,
            x.rareAt,
            x.rareIds,
            x.rareWeights,
            cum,
            total,
            y.at,
            y.items,
            y.weights,
            familyOf,
            n,
            models,
            weights,
            Logistic.FLAT_HEAD + 2 * families,
            p,
        )
    }

    /** Every model's score of every pair of the [m] rows from [from] on, of every row every model's likeliest work. */
    private fun chosen(from: Int, threads: Long, m: Int, kernels: CudaKernels): IntArray {
        kernels.launch(
            "best",
            threads,
            m,
            from,
            n,
            own.count,
            t,
            d,
            a,
            p,
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
            x.frequentAt,
            x.frequentIds,
            x.frequentWeights,
            kinAt,
            kinEntries,
            ownAuthorAt,
            ownAuthorEntries,
            tv,
            dl,
            if (titleVectors) 1 else 0,
            x.titled,
            y.titled,
            x.letterLengths,
            y.letterLengths,
            y.frequentAt,
            y.frequentIds,
            y.frequentWeights,
            y.authorAt,
            y.authorPlaces,
            x.frequent.family.size,
            frequentFamily,
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
         * The memory a block's planes take on the card at most, halved while the card has no room for it
         * ([NoCardRoom]): every block reads every work of the other site's vectors again — the more rows a block, the
         * fewer readings.
         */
        const val MOST_ROOM = 4L shl 30

        /** The least memory of a block's planes tried before the search is given to the processor. */
        const val LEAST_ROOM = 64L shl 20

        /**
         * The planes of a row's every pair: the titles, the authors' names, the descriptions, the titles' vectors, the
         * trigrams of every other text.
         */
        val PLANES = 4 + LETTERED

        /**
         * The share of the room left a block's planes take: the rest the rows of the scores and the pinned memory's.
         */
        const val ROOM_SHARE = 3L
        const val ROOM_PARTS = 4L

        /**
         * The rows of a block of the kernel of the scores (`ROWS`): a thread reads a work of the other site once for
         * them.
         */
        const val ROWS = 8

        /** The numbers of size of a pair the kernel holds at most (`SIZES`). */
        const val MOST_SIZES = 8

        /** A work's authors and their kin the kernel holds at most (`KIN`). */
        const val MOST_KIN = 64

        /** The facets the kernel sums apart at most (`MAX_FAMILIES`). */
        const val MOST_FAMILIES = 64
    }
}

/** Lists of every row one after another: [at] where a row's begin among [entries]. */
internal class Lists(val at: IntArray, val entries: IntArray) {
    companion object {
        fun of(rows: List<IntArray>): Lists {
            val at = IntArray(rows.size + 1)
            rows.forEachIndexed { i, own -> at[i + 1] = at[i] + own.size }
            val entries =
                IntArray(at[rows.size]).also { flat -> rows.forEachIndexed { i, own -> own.copyInto(flat, at[i]) } }
            return Lists(at, entries)
        }
    }
}
