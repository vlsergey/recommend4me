package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.tag.TagEntryRef

/** Two works of two authors, what is known of them: the user's word they match by, the chance they are one. */
internal class WorkPair(val first: ItemKey, val second: ItemKey, val answer: DuplicateAnswer?, val chance: Double?) {
    companion object {
        /**
         * Whether a pair of works of the word [answer] tells of its authors: one work or a part of the other, or none
         * said — its chance. A translation proves no author, its translator another; different works tell nothing.
         */
        fun tells(answer: DuplicateAnswer?) =
            answer == null || answer == DuplicateAnswer.SAME || answer == DuplicateAnswer.PARTIAL
    }
}

/** What is known of two authors by the pairs of their works: how many matching, the chances of the rest summed. */
internal class Tally {
    var matching = 0
    var expected = 0.0
}

/**
 * WHAT THE PAIRS OF WORKS TELL OF THE PAIRS OF THEIR AUTHORS, of the authors of the works of the [library]: every pair
 * added ([add]) counted to every pair of its authors ([tallies]).
 */
internal class AuthorEvidence(private val library: AuthorLibrary) {
    val tallies = HashMap<Pair<Long, Long>, Tally>()

    /** The [pair] of works counted to every pair of its authors: matching of a word, else by its chance. */
    fun add(pair: WorkPair) =
        forEachAuthors(pair) { authors, _ ->
            val tally = tallies.getOrPut(authors) { Tally() }
            if (pair.answer != null) tally.matching++ else tally.expected += pair.chance ?: 0.0
        }

    /**
     * The two authors every pair of works tells of, as a pair of ids the smaller first, with the pair of works first of
     * the first: of every author of one work and every author of the other, but those who wrote one of the two together
     * — two writers of one work are not one by it.
     */
    fun forEachAuthors(pair: WorkPair, action: (Pair<Long, Long>, Boolean) -> Unit) {
        val ofFirst = library.authorsOf[pair.first].orEmpty()
        val ofSecond = library.authorsOf[pair.second].orEmpty()
        for (a in ofFirst) for (b in ofSecond) {
            if (a == b || b in ofFirst || a in ofSecond) continue
            action(AuthorRepository.pair(a, b), a <= b)
        }
    }

    companion object {
        /** The pairs of the authors [bound] to one entry of a catalogue, the smaller id first. */
        fun oneEntry(bound: Map<Long, List<TagEntryRef>>): Set<Pair<Long, Long>> =
            HashMap<TagEntryRef, MutableList<Long>>()
                .also { sharing ->
                    bound.forEach { (id, refs) -> refs.forEach { sharing.getOrPut(it) { ArrayList() } += id } }
                }
                .values
                .flatMap { ids ->
                    ids.flatMapIndexed { k, a -> ids.drop(k + 1).map { b -> AuthorRepository.pair(a, b) } }
                }
                .toSet()
    }
}
