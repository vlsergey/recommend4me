package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import java.util.IdentityHashMap
import java.util.PriorityQueue

/**
 * THE WORKS THE MODEL READS AS NEIGHBOURS, [room] at most of every work of the portions, gathered over one walk
 * ([offer] of every portion): every work that says anything of the values (a pair), those the user answered on first,
 * then the rest in an order of their ids alone — a sample the same at every walk, whatever the portions and whatever
 * else the source holds. Held as they are offered: never more than [room] works, a work falling out once [room] better
 * ones came. Of every work kept, where it lies ([Neighbours.DIMENSIONS] numbers), what it says of the values, its fold
 * and the values the user answered on of it.
 *
 * [layout]: the name and the width of every block of input, as every work is projected of them.
 */
internal class ReferenceSet(private val room: Int, private val m: Int, private val layout: List<Pair<String, Int>>) {
    /** The works kept, the one to fall out first at the head. */
    private val kept = PriorityQueue<Entry>(room.coerceAtLeast(1)) { a, b -> BETTER.compare(b.rank, a.rank) }

    /** The arrays of the values the works kept may have, one of each content. */
    private val mays = Mays()

    /** How many works answered on were offered: those left out are held out of a walk of their own. */
    var answered = 0
        private set

    /** The works of a [portion] whose [pairs] say anything, kept as long as they are among the best [room]. */
    fun offer(portion: SuggestionTask, pairs: SuggestionPairs) {
        val came = ArrayList<Entry>()
        for (i in 0 until portion.itemCount) {
            val asked = portion.confirmed[i] + portion.rejected[i]
            if (asked.isNotEmpty()) answered++
            if (pairs.count[i] > 0) admitted(portion.itemIds[i], i, asked, pairs)?.let(came::add)
        }
        place(portion, came.filter { !it.out })
    }

    /**
     * The work [id] of the row [i] of a portion, its values [asked] of the user and its [pairs], kept when it is among
     * the best [room] of those offered so far — the worst kept falling out — else null.
     */
    private fun admitted(id: String, i: Int, asked: IntArray, pairs: SuggestionPairs): Entry? {
        val rank = Rank(asked.isNotEmpty(), mixed(id.hashCode()), id)
        val worst = kept.peek()
        if (kept.size >= room && (worst == null || BETTER.compare(rank, worst.rank) >= 0)) return null
        val entry = Entry(rank, i, pairs.foldOf[i], asked, pairs.lack[i], pairs.may[i]?.let(mays::take), pairs)
        kept += entry
        if (kept.size > room) kept.poll().also { it.out = true }.may?.let(mays::give)
        return entry
    }

    /** Where every work of [entries] of the [portion] lies: its own inputs alone projected. */
    private fun place(portion: SuggestionTask, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val few = portion.ofItems(IntArray(entries.size) { entries[it].row })
        val y =
            requireNotNull(Neighbours.project(few.inputs, few.itemCount, layout)) {
                "A portion of other blocks of input than the facet's: ${few.inputs.map { it.name to it.dim }}"
            }
        entries.forEachIndexed { r, e ->
            e.vector = FloatArray(Neighbours.DIMENSIONS).also { y.take(it, 0, r * Neighbours.DIMENSIONS, it.size) }
        }
    }

    /** The works kept, the best first. */
    fun gathered(): Reference {
        val entries = kept.sortedWith { a, b -> BETTER.compare(a.rank, b.rank) }
        val vectors = Floats((entries.size * Neighbours.DIMENSIONS).coerceAtLeast(1))
        entries.forEachIndexed { r, e -> vectors.put(e.vector, 0, r * Neighbours.DIMENSIONS, Neighbours.DIMENSIONS) }
        // One array of each content already: told apart by identity
        val shared = entries.mapNotNull { it.may }.distinct()
        val mayOf = IdentityHashMap<IntArray, Int>().also { at -> shared.forEach { at[it] = at.size } }
        val labels =
            Labels(
                m,
                FloatArray(entries.size) { entries[it].lack },
                shared,
                IntArray(entries.size) { r -> entries[r].may?.let { mayOf.getValue(it) } ?: -1 },
                Array(entries.size) { entries[it].own },
                Array(entries.size) { entries[it].ownWeight },
                Array(entries.size) { entries[it].ownLabel },
            )
        return Reference(
            entries.map { it.rank.id },
            vectors,
            labels,
            IntArray(entries.size) { entries[it].fold },
            entries.map { it.asked },
            answered - entries.count { it.asked.isNotEmpty() },
        )
    }

    /** Where a work stands among the works offered: answered on first, then by its mixed hash, then by its id. */
    private class Rank(val answered: Boolean, val hash: Long, val id: String)

    /** A work kept: of the row [row] of the portion it came in, its pairs copied out of [pairs]. */
    private class Entry(
        val rank: Rank,
        val row: Int,
        val fold: Int,
        val asked: IntArray,
        val lack: Float,
        val may: IntArray?,
        pairs: SuggestionPairs,
    ) {
        val own: IntArray = pairs.own[row]
        val ownWeight: FloatArray = pairs.ownWeight[row]
        val ownLabel: FloatArray = pairs.ownLabel[row]
        lateinit var vector: FloatArray

        /** Whether it fell out of the works kept. */
        var out = false
    }

    /**
     * THE ARRAYS OF THE VALUES THE WORKS KEPT MAY HAVE, one of each content however many portions brought it — the
     * works of a universe share its thousands of characters — and as long as a work kept has it.
     */
    private class Mays {
        private val held = HashMap<Content, Pair<IntArray, IntArray>>()

        /** The array of the content of [a], kept once more. */
        fun take(a: IntArray): IntArray {
            val (array, uses) = held.getOrPut(Content(a)) { a to IntArray(1) }
            uses[0]++
            return array
        }

        /** The array [a] given back by a work that fell out: forgotten once no work kept has it. */
        fun give(a: IntArray) {
            val key = Content(a)
            val uses = held.getValue(key).second
            if (--uses[0] == 0) held.remove(key)
        }

        private class Content(val a: IntArray) {
            private val hash = a.contentHashCode()

            override fun hashCode() = hash

            override fun equals(other: Any?) = other is Content && other.hash == hash && other.a.contentEquals(a)
        }
    }

    private companion object {
        /** The better first: answered on, then the lower hash, then the lower id. */
        val BETTER: Comparator<Rank> = compareByDescending<Rank> { it.answered }.thenBy { it.hash }.thenBy { it.id }

        /** The constants of the finalizer of MurmurHash3: an id's hash spread over every bit. */
        const val MIX1 = -0xae502812aa7333L
        const val MIX2 = -0x3b314601e57a13adL
        const val SHIFT = 33

        /** The hash of an id spread over every bit, the same at every walk and every run. */
        fun mixed(hash: Int): Long {
            var h = hash.toLong()
            h = (h xor (h ushr SHIFT)) * MIX1
            h = (h xor (h ushr SHIFT)) * MIX2
            return h xor (h ushr SHIFT)
        }
    }
}

/**
 * THE WORKS GATHERED AS NEIGHBOURS ([ReferenceSet]), the best first: their [ids], where each lies ([vectors],
 * [Neighbours.DIMENSIONS] numbers each), what each says of the values ([labels]), the fold of each ([folds]) and the
 * values the user answered on of each ([asked]); [leftOut]: how many works the user answered on are not among them.
 */
internal class Reference(
    val ids: List<String>,
    val vectors: Floats,
    val labels: Labels,
    val folds: IntArray,
    val asked: List<IntArray>,
    val leftOut: Int,
) {
    val size: Int
        get() = ids.size
}
