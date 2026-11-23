package io.github.vlsergey.recommend4me.duplicate

import java.util.Arrays
import java.util.stream.IntStream

/**
 * THE WORKS OF ONE AUTHOR AND OF ONE TITLE, of every work of a search by the graph ([AllWorks], one numbering): every
 * one a candidate of a work however near the graph finds it ([candidates]), and the graph begun of them ([Seeds]).
 * Every list made in one array of numbers — of every work the length of its row first, then the rows filled where they
 * begin, the works in parallel —, not a list of boxed numbers a work: of the 107 million candidates of the books, those
 * took 6.8 s (2026-11-23).
 */
internal object WorksAlike {
    /** Of every work, the other works of an author one with its, by the kin of its authors: ascending, each once. */
    fun ofAuthors(all: AllWorks): Lists {
        val n = all.count
        val authors = (all.authors.entries.maxOrNull() ?: -1) + 1
        // Of every author its works, side by side
        val from = IntArray(authors + 1)
        all.authors.entries.forEach { from[it + 1]++ }
        for (a in 0 until authors) from[a + 1] += from[a]
        val works = IntArray(all.authors.entries.size)
        val filled = from.copyOf(authors)
        for (u in 0 until n) for (q in all.authors.at[u] until all.authors.at[u + 1]) {
            works[filled[all.authors.entries[q]]++] = u
        }
        // Of every work a room of what its row may hold — every work of every author of its kin —, filled, sorted and
        // made of every work once, but its own
        val room = IntArray(n + 1)
        for (u in 0 until n) {
            var size = 0
            for (q in all.kin.at[u] until all.kin.at[u + 1]) {
                val a = all.kin.entries[q]
                if (a < authors) size += from[a + 1] - from[a]
            }
            room[u + 1] = Math.addExact(room[u], size)
        }
        val entries = IntArray(room[n])
        val sizes = IntArray(n)
        IntStream.range(0, n).parallel().forEach { u ->
            var at = room[u]
            for (q in all.kin.at[u] until all.kin.at[u + 1]) {
                val a = all.kin.entries[q]
                if (a < authors) for (p in from[a] until from[a + 1]) entries[at++] = works[p]
            }
            entries.sort(room[u], at)
            sizes[u] = distinctBut(entries, room[u], at, u)
        }
        return packed(room, sizes, entries)
    }

    /**
     * Of every work the works of an author one with its ([ofAuthors]), then those of its very title ([titles]) not
     * among them, in their order.
     */
    fun candidates(ofAuthors: Lists, titles: Lists): Lists {
        val n = ofAuthors.at.size - 1
        val sizes = IntArray(n)
        IntStream.range(0, n).parallel().forEach { u ->
            sizes[u] = ofAuthors.at[u + 1] - ofAuthors.at[u] + titledApart(u, ofAuthors, titles, null, 0)
        }
        val at = IntArray(n + 1)
        for (u in 0 until n) at[u + 1] = Math.addExact(at[u], sizes[u])
        val entries = IntArray(at[n])
        IntStream.range(0, n).parallel().forEach { u ->
            val own = ofAuthors.at[u + 1] - ofAuthors.at[u]
            System.arraycopy(ofAuthors.entries, ofAuthors.at[u], entries, at[u], own)
            titledApart(u, ofAuthors, titles, entries, at[u] + own)
        }
        return Lists(at, entries)
    }

    /**
     * Of the [u]-th work, its [titles] not among its [ofAuthors]: written into [into] from [from] when given; how many.
     */
    private fun titledApart(u: Int, ofAuthors: Lists, titles: Lists, into: IntArray?, from: Int): Int {
        var size = 0
        for (q in titles.at[u] until titles.at[u + 1]) {
            val v = titles.entries[q]
            if (Arrays.binarySearch(ofAuthors.entries, ofAuthors.at[u], ofAuthors.at[u + 1], v) < 0) {
                into?.set(from + size, v)
                size++
            }
        }
        return size
    }

    /**
     * The sorted [entries] from [from] to [to] made of every number once, but [but], from [from] on: how many are left.
     */
    private fun distinctBut(entries: IntArray, from: Int, to: Int, but: Int): Int {
        var size = 0
        for (q in from until to) {
            val v = entries[q]
            if (v != but && (size == 0 || entries[from + size - 1] != v)) entries[from + size++] = v
        }
        return size
    }

    /** The rows of the [sizes] of every work, each begun at its [room] among the [entries], put one after another. */
    private fun packed(room: IntArray, sizes: IntArray, entries: IntArray): Lists {
        val n = sizes.size
        val at = IntArray(n + 1)
        for (u in 0 until n) {
            at[u + 1] = at[u] + sizes[u]
            // A row never moved right: its room begins where it does, or after
            System.arraycopy(entries, room[u], entries, at[u], sizes[u])
        }
        return Lists(at, if (at[n] == entries.size) entries else entries.copyOf(at[n]))
    }
}

/**
 * THE WORKS OF THE VERY SAME TITLE: of every work the hash of its title's trigrams, the works of one hash one title —
 * of a title many works bear, the [MOST] nearest it in the numbering ([same], the nearest first, one after the other
 * side). [shared] the titles several works bear, [largest] the most works of one.
 */
internal class Titles private constructor(val same: Lists, val shared: Int, val largest: Int) {
    companion object {
        /** The works of one title taken of a work at most. */
        const val MOST = 256

        private const val HASH_START = 1125899906842597L
        private const val HASH_PRIME = 31L

        fun of(all: AllWorks): Titles {
            val hashes = LongArray(all.count)
            all.sites.forEachIndexed { a, s ->
                val row = FloatArray(s.width)
                for (i in 0 until s.count) {
                    s.titles.take(i, row)
                    var h = HASH_START
                    row.forEach { h = h * HASH_PRIME + java.lang.Float.floatToIntBits(it) }
                    hashes[all.start[a] + i] = h
                }
            }
            val ordered = Ordered.of(hashes)
            val n = all.count
            val sizes = IntArray(n)
            IntStream.range(0, n).parallel().forEach { u -> sizes[u] = ordered.near(u, null, 0) }
            val at = IntArray(n + 1)
            for (u in 0 until n) at[u + 1] = Math.addExact(at[u], sizes[u])
            val entries = IntArray(at[n])
            IntStream.range(0, n).parallel().forEach { u -> ordered.near(u, entries, at[u]) }
            return Titles(Lists(at, entries), ordered.shared, ordered.largest)
        }
    }

    /**
     * The works in the order of their titles' [hashes], of one hash by their numbers ([order]), and the place of every
     * work in it ([place]).
     */
    private class Ordered(private val hashes: LongArray, private val order: IntArray, private val place: IntArray) {
        /**
         * The other works of the [u]-th one's title, the nearest first, of a step the one before then the one after,
         * till a step finds none or [MOST] are found: written into [into] from [from] when given; how many.
         */
        fun near(u: Int, into: IntArray?, from: Int): Int {
            var size = 0
            var step = 1
            while (size < MOST && step <= MOST) {
                var any = false
                for (at in intArrayOf(place[u] - step, place[u] + step)) {
                    if (at in order.indices && hashes[order[at]] == hashes[u]) {
                        into?.set(from + size, order[at])
                        size++
                        any = true
                    }
                }
                if (!any) break
                step++
            }
            return size
        }

        /** The titles several works bear. */
        var shared = 0
            private set

        /** The most works of one title. */
        var largest = 0
            private set

        init {
            var from = 0
            while (from < order.size) {
                var to = from + 1
                while (to < order.size && hashes[order[to]] == hashes[order[from]]) to++
                if (to - from > 1) shared++
                largest = maxOf(largest, to - from)
                from = to
            }
        }

        companion object {
            /**
             * The works ordered by their [hashes], of one by their numbers: of every work the rank of its hash among
             * those sorted, the rank and the number sorted as one number.
             */
            fun of(hashes: LongArray): Ordered {
                val n = hashes.size
                val distinct = hashes.copyOf().also { it.sort() }
                var kinds = 0
                for (q in distinct.indices) if (kinds == 0 || distinct[kinds - 1] != distinct[q]) {
                    distinct[kinds++] = distinct[q]
                }
                val keyed =
                    LongArray(n) { u ->
                        (Arrays.binarySearch(distinct, 0, kinds, hashes[u]).toLong() shl Int.SIZE_BITS) or u.toLong()
                    }
                Arrays.parallelSort(keyed)
                val order = IntArray(n) { keyed[it].toInt() }
                val place = IntArray(n).also { p -> order.forEachIndexed { at, u -> p[u] = at } }
                return Ordered(hashes, order, place)
            }
        }
    }
}
