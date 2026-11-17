package io.github.vlsergey.recommend4me.interest

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.stereotype.Component

/**
 * THE ORDER THE BACKGROUND SERVES A SOURCE'S WORKS IN when it cannot serve them all at once — the texts of a million
 * works of a dump encoded a portion at a time (user, 2026-11-17): the graded first, the model learns from them; then by
 * what the list sorted by the formula sorts by ([Interest.expected]: the prediction times the chance of interest), the
 * likeliest first; then those not predicted yet. Read at the start of every portion, the predictions changing between.
 */
@Component
class LikeliestFirst(private val stores: Stores) {
    /** The items [ids] of the source [store] in this order, each once. */
    fun of(store: SourceStore, ids: Collection<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val graded = store.ratings.gradedItems()
        val predicted = stores.typeOf(store.id).predictions.predicted(store.id, ids)
        val marks = store.interest.all()
        val expected = { id: String ->
            predicted[id]?.let { Interest.expected(it.score, Interest.chance(marks[id]?.interesting, it.interest)) }
        }
        return ids.distinct()
            .sortedWith(
                compareByDescending<String> { it in graded }
                    .thenByDescending { expected(it) ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it }
            )
    }
}
