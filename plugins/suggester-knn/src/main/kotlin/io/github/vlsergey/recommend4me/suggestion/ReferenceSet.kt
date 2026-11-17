package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import java.util.IdentityHashMap
import java.util.PriorityQueue

/**
 * THE WORKS THE MODEL READS AS NEIGHBOURS, [room] at most of every work of the portions, chosen over one walk ([offer]
 * of every portion) by their ids alone: every work that says anything of the values (a pair), those the user answered
 * on first, then the rest in an order of their ids — a sample the same at every walk, whatever the portions and
 * whatever else the source holds. Held as they are offered: never more than [room] works, a work falling out once
 * [room] better ones came. Of every work kept, what it says of the values, its fold and the values the user answered on
 * of it; where it lies is not known before every portion has shown its blocks of input, and is placed in a walk of its
 * own ([Reference.place]).
 */
internal class ReferenceSet(private val room: Int, private val m: Int) {
    /** The works kept, the one to fall out first at the head. */
    private val kept = PriorityQueue<Entry>(room.coerceAtLeast(1)) { a, b -> BETTER.compare(b.rank, a.rank) }

    /** The arrays of the values the works kept may have, one of each content. */
    private val mays = Mays()

    /** How many works answered on were offered: those left out are held out of a walk of their own. */
    var answered = 0
        private set

    /** The works of a [portion] whose [pairs] say anything, kept as long as they are among the best [room]. */
    fun offer(portion: SuggestionTask, pairs: SuggestionPairs) {
        for (i in 0 until portion.itemCount) {
            val asked = portion.confirmed[i] + portion.rejected[i]
            if (asked.isNotEmpty()) answered++
            if (pairs.count[i] > 0) admit(portion.itemIds[i], i, asked, pairs)
        }
    }

    /**
     * The work [id] of the row [i] of a portion, its values [asked] of the user and its [pairs], kept when it is among
     * the best [room] of those offered so far — the worst kept falling out.
     */
    private fun admit(id: String, i: Int, asked: IntArray, pairs: SuggestionPairs) {
        val rank = Rank(asked.isNotEmpty(), mixed(id.hashCode()), id)
        val worst = kept.peek()
        if (kept.size >= room && (worst == null || BETTER.compare(rank, worst.rank) >= 0)) return
        kept += Entry(rank, i, pairs.foldOf[i], asked, pairs.lack[i], pairs.may[i]?.let(mays::take), pairs)
        if (kept.size > room) kept.poll().may?.let(mays::give)
    }

    /** The works kept, the best first, of the [layout] of the blocks of input; where each lies not placed yet. */
    fun gathered(layout: List<Pair<String, Int>>): Reference {
        val entries = kept.sortedWith { a, b -> BETTER.compare(a.rank, b.rank) }
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
            layout,
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
        row: Int,
        val fold: Int,
        val asked: IntArray,
        val lack: Float,
        val may: IntArray?,
        pairs: SuggestionPairs,
    ) {
        val own: IntArray = pairs.own[row]
        val ownWeight: FloatArray = pairs.ownWeight[row]
        val ownLabel: FloatArray = pairs.ownLabel[row]
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
 * THE WORKS GATHERED AS NEIGHBOURS ([ReferenceSet]), the best first: their [ids], what each says of the values
 * ([labels]), the fold of each ([folds]) and the values the user answered on of each ([asked]); [leftOut]: how many
 * works the user answered on are not among them. Where each lies ([vectors], [Neighbours.DIMENSIONS] numbers each) is
 * placed a portion at a time ([place]), every work projected of the [layout] of every block some portion has.
 */
internal class Reference(
    val ids: List<String>,
    val layout: List<Pair<String, Int>>,
    val labels: Labels,
    val folds: IntArray,
    val asked: List<IntArray>,
    val leftOut: Int,
) {
    val size: Int
        get() = ids.size

    val vectors = Floats((size * Neighbours.DIMENSIONS).coerceAtLeast(1))

    /** The row of every work gathered, by its id. */
    private val rowOf = ids.withIndex().associate { (r, id) -> id to r }

    /**
     * Where every work gathered among those of the [portion] lies: its own inputs alone projected of the [layout], a
     * block it lacks nought.
     */
    fun place(portion: SuggestionTask) {
        val rows = (0 until portion.itemCount).filter { portion.itemIds[it] in rowOf }
        if (rows.isEmpty()) return
        val few = portion.ofItems(rows.toIntArray())
        val y =
            requireNotNull(Neighbours.project(few.inputs, few.itemCount, layout)) {
                "A block of input of another width than the first portion's: ${few.inputs.map { it.name to it.dim }}"
            }
        val vector = FloatArray(Neighbours.DIMENSIONS)
        for (r in 0 until few.itemCount) {
            y.take(vector, 0, r * Neighbours.DIMENSIONS, vector.size)
            vectors.put(vector, 0, rowOf.getValue(few.itemIds[r]) * Neighbours.DIMENSIONS, vector.size)
        }
    }
}
