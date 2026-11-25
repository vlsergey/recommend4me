package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * THE SEARCH BY MEANING, of what the application found nearest to the line ([SearchContext.nearest]): how close every
 * item is on the line's own scale, and what is shown of an item found by it. "corruption of a nun" finds a game whose
 * overview never says "corruption".
 */
internal object MeaningSearch {

    /**
     * How close the items nearest to the query by meaning are, ON THE QUERY'S OWN SCALE: 0 at the [NEAREST]th nearest
     * item — or at the median one of a smaller catalogue — 1 at the top percent of the catalogue of [size] items; the
     * items farther than that are not matches by meaning. Raw scores are no scale at all — e5 puts nearly every pair of
     * texts between 0.7 and 0.9 — and a fixed threshold either lets everything through or nothing.
     */
    fun closeness(found: Map<ItemKey, SearchNearest>, size: Int): Map<ItemKey, Double> {
        val nearest = found.mapValues { it.value.score }
        if (nearest.size < 2) return nearest
        val sorted = nearest.values.sortedDescending()
        val floor = sorted[minOf(NEAREST, size / 2, sorted.size).coerceAtLeast(1) - 1]
        val top = sorted[(size / 100).coerceIn(0, sorted.size - 1)]
        val span = (top - floor).coerceAtLeast(1e-6)
        return nearest.mapValues { (_, cos) -> ((cos - floor) / span).coerceIn(0.0, MAX_CLOSENESS) }
    }

    /**
     * What the work [doc] was found by, by meaning: the [passage] of it nearest to the line, whole — the text whose
     * vector was near, not a sentence of it picked apart, which may say less of the line than the whole. Null of a work
     * found by no passage, or of a passage of no text.
     */
    fun passageOf(passage: SearchPassage?, doc: SearchDocument): SearchMatch? {
        val text = passage?.text(doc)?.trim().orEmpty()
        return if (passage == null || text.isEmpty() || doc.fields.none { it.key == passage.field }) null
        else SearchMatch(passage.field, true, text, emptyList())
    }

    /** The item the closeness is nought at: the farther ones are no matches by meaning. */
    private const val NEAREST = 2000

    /** The few items above the top percent are not told apart further than this. */
    const val MAX_CLOSENESS = 1.5
}
