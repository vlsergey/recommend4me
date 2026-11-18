package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import kotlin.concurrent.withLock

/**
 * THE SEARCH ON THE CARD ([ProcessorSearch]'s, made there): every site's works put on the card once — their titles' and
 * descriptions' vectors, what they have ([Inverted]), their sizes — and of every two sites, and of every site with
 * itself, a block of rows of one against the whole other at a time ([Facing]): the cosines of their titles and
 * descriptions by two products of cuBLAS, the facets their values share and their works of one author by kernels of
 * their own (`search.cu`), and every model's score of every pair in one more, which keeps of every row the likeliest
 * work of every model with its score; of every work the likeliest of every site kept ([Likeliest]). Nothing of a pair
 * comes back but the works kept and their scores.
 */
internal object CardSearch {
    private val NAMES = listOf("facets_onto", "authors_onto", "best")

    private var compiledFor: Cublas? = null
    private var compiled: CudaKernels? = null

    @Synchronized
    private fun kernels(card: Cublas): CudaKernels? {
        if (compiledFor !== card) {
            compiledFor = card
            compiled = card.kernels(CardSearch::class.java.getResource("search.cu")!!.readText(), NAMES)
        }
        return compiled
    }

    /** The pairs of the [search] by the [models] on the [card]: every site cut into runs of [slice] works at most. */
    fun search(
        search: PairSearch,
        models: List<Logistic.Laid>,
        card: Cublas,
        slice: Int = Tiles.SLICE,
    ): List<FoundPair> =
        card.lock.withLock {
            val kernels = kernels(card) ?: error("the kernels of the search were not made")
            val likeliest = Likeliest(search, models.size)
            val slices = search.sites.map { Tiles.slices(it, slice) }
            // Every run's works by what they have, made once: a run is faced by every run of every site
            val inverted = slices.map { runs -> runs.map { Inverted(it.works, search.familyOf.size) } }
            for (a in search.sites.indices) for (b in search.sites.indices) {
                if (!search.compared(a, b)) continue
                // The weights of the models as the two sites are scored: their sites' weight in the constant
                val between =
                    models
                        .map { it.between(search.sites[a].source, search.sites[b].source) }
                        .flatMap { it.flat().asList() }
                        .toFloatArray()
                // Of every run of the first site on the card, every run of the second put there in its turn
                for ((r, rows) in slices[a].withIndex()) CardRoom(card).use { ofRows ->
                    val x = SiteOnCard(rows.works, inverted[a][r], ofRows)
                    for ((c, columns) in slices[b].withIndex()) {
                        val tile = Tile(search, a, b, rows, columns)
                        val y = inverted[b][c]
                        // The block as large as the card has room for: halved till it has
                        var planes = Facing.MOST_ROOM
                        while (true) {
                            try {
                                CardRoom(card).use { own ->
                                    val weights = own.floatsOf(between)
                                    Facing(
                                            tile,
                                            x,
                                            SiteOnCard(columns.works, y, own),
                                            models.size,
                                            weights,
                                            own,
                                            planes,
                                        )
                                        .offer(kernels, card, likeliest)
                                }
                                break
                            } catch (e: NoRoom) {
                                if (planes <= Facing.LEAST_ROOM) throw e
                                planes /= 2
                            }
                        }
                    }
                }
            }
            likeliest.found()
        }
}

/** No room on the card for what the search needs there: it is the main memory's. */
internal class NoRoom : RuntimeException("no room on the card")

/** What is put on the card and freed with the search, or with a pair of sites. */
internal class CardRoom(private val card: Cublas) : AutoCloseable {
    private val made = ArrayList<AutoCloseable>()

    fun floats(count: Long): Cublas.DeviceFloats =
        (card.allocate(maxOf(1L, count)) ?: throw NoRoom()).also { made += it }

    fun bytes(count: Long): Cublas.DeviceBytes =
        (card.allocateBytes(maxOf(1L, count)) ?: throw NoRoom()).also { made += it }

    fun ints(a: IntArray): Cublas.DeviceBytes = bytes(a.size.toLong() * Int.SIZE_BYTES).also { it.upload(a) }

    fun floatsOf(a: FloatArray): Cublas.DeviceBytes = bytes(a.size.toLong() * Float.SIZE_BYTES).also { it.upload(a) }

    fun longs(a: LongArray): Cublas.DeviceBytes = bytes(a.size.toLong() * Long.SIZE_BYTES).also { it.upload(a) }

    /** Page-locked main memory of [bytes]: what is brought back from the card is copied into it. */
    fun pinned(bytes: Long): Cublas.Pinned = (card.pinned(maxOf(1L, bytes)) ?: throw NoRoom()).also { made += it }

    /** Marks a byte each, as a kernel reads them: [Int.SIZE_BYTES] to an int, little-endian. */
    fun marks(marks: BooleanArray): Cublas.DeviceBytes {
        val ints = IntArray((marks.size + Int.SIZE_BYTES - 1) / Int.SIZE_BYTES)
        marks.forEachIndexed { i, m ->
            if (m)
                ints[i / Int.SIZE_BYTES] = ints[i / Int.SIZE_BYTES] or (1 shl (Byte.SIZE_BITS * (i % Int.SIZE_BYTES)))
        }
        return ints(ints)
    }

    override fun close() = made.forEach { it.close() }
}

/**
 * A site on the card: its vectors, whether it has a description, its facets and sizes ([SiteWorks.sizes] one row after
 * another), and its works by what they have.
 */
internal class SiteOnCard(site: SiteWorks, val inverted: Inverted, room: CardRoom) {
    // Half precision, as the site's works hold them: half the room of single precision's (a million works of 1024
    // numbers 6 GB of the card's 16, not 12)
    val titles =
        room.bytes(site.count.toLong() * site.width * HALF_BYTES).also { it.upload(site.titles, 0, site.count) }
    val authorNames =
        room.bytes(site.count.toLong() * site.width * HALF_BYTES).also { it.upload(site.authorNames, 0, site.count) }
    val titleLengths = room.floatsOf(site.titleLengths)
    val authorLengths = room.floatsOf(site.authorLengths)
    val descriptions =
        room.bytes(site.count.toLong() * site.descriptions.cols * HALF_BYTES).also {
            it.upload(site.descriptions, 0, site.count)
        }
    val described = room.marks(site.described)
    val facets = room.longs(site.facets)
    val sizes =
        room.floatsOf(
            FloatArray(site.sizes.size * site.count) { site.sizes[it / site.count][it % site.count].toFloat() }
        )
    val at = room.ints(inverted.at)
    val items = room.ints(inverted.items)
    val weights = room.floatsOf(inverted.weights)
    val authorAt = room.ints(inverted.authorAt)
    val authorItems = room.ints(inverted.authorItems)
}

/**
 * Lists of every row one after another ([at] where a row's begin among [entries]) — and of every entry, where the list
 * of the other site's works it stands for begins among the row's ([cum]), how long they are together ([total]): a
 * kernel's thread an entry of those lists, whatever the lists' lengths.
 */
internal class RowLists(val at: IntArray, val entries: IntArray, val cum: IntArray, val total: IntArray) {
    companion object {
        /** The [rows]' lists, of every entry's list as long as [length] says. */
        fun of(rows: List<IntArray>, length: (Int) -> Int): RowLists {
            val at = IntArray(rows.size + 1)
            rows.forEachIndexed { i, own -> at[i + 1] = at[i] + own.size }
            val entries =
                IntArray(at[rows.size]).also { flat -> rows.forEachIndexed { i, own -> own.copyInto(flat, at[i]) } }
            val cum = IntArray(entries.size)
            val total = IntArray(rows.size)
            for (i in rows.indices) {
                var sum = 0
                for (k in at[i] until at[i + 1]) {
                    cum[k] = sum
                    sum += length(entries[k])
                }
                total[i] = sum
            }
            return RowLists(at, entries, cum, total)
        }
    }
}

/** The bytes of a number of half precision. */
internal const val HALF_BYTES = 2L
