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

    fun search(search: PairSearch, models: List<Logistic.Laid>, card: Cublas): List<FoundPair> =
        card.lock.withLock {
            val kernels = kernels(card) ?: error("the kernels of the search were not made")
            CardRoom(card).use { room ->
                val sites = search.sites.map { s -> SiteOnCard(s, Inverted(s, search.familyOf.size), room) }
                val likeliest = Likeliest(search, models.size)
                for (a in sites.indices) for (b in sites.indices) {
                    if (!search.compared(a, b)) continue
                    CardRoom(card).use { own ->
                        // The weights of the models as the two sites are scored: their sites' weight in the constant
                        val between = models.map { it.between(search.sites[a].source, search.sites[b].source) }
                        val weights = own.floatsOf(between.flatMap { it.flat().asList() }.toFloatArray())
                        Facing(search, a, b, sites[a], sites[b], models.size, weights, own)
                            .offer(kernels, card, likeliest)
                    }
                }
                likeliest.found()
            }
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
    val titles =
        room.floats(site.count.toLong() * site.width).also {
            it.upload(site.titles.held, 0, 0, site.count * site.width)
        }
    val descriptions =
        room.floats(site.count.toLong() * site.width).also {
            it.upload(site.descriptions.held, 0, 0, site.count * site.width)
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
