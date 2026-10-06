package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix

/**
 * Everything a suggester knows of one facet of one source ([FacetDef.suggest][io.github.vlsergey.recommend4me.source.FacetDef.suggest]):
 * every item — the vector of its text and the values it has — and every value that may be
 * suggested, with the vector of its name.
 *
 * Rows of [items] and of the lists are items, rows of [values] values; the lists hold indices of
 * [values].
 */
class SuggestionTask(
    /** One row per item, of unit length; a row of zeros for an item whose text is not encoded. */
    val items: Matrix,
    /** One row per value: the vector of its name as a query, of unit length; zeros when not encoded. */
    val values: Matrix,
    /** The values each item has now: the site's, with the user's corrections applied. */
    val assigned: List<IntArray>,
    /** The values the user said the item has: added by hand or accepted when suggested. */
    val confirmed: List<IntArray>,
    /** The values the user said the item does not have: taken from it, or rejected when suggested. */
    val rejected: List<IntArray>,
) {
    init {
        require(items.rows == assigned.size && items.rows == confirmed.size && items.rows == rejected.size) {
            "${items.rows} items, ${assigned.size} assigned, ${confirmed.size} confirmed, ${rejected.size} rejected"
        }
        require(items.cols == values.cols) { "Items of ${items.cols} numbers, values of ${values.cols}" }
    }

    val itemCount: Int get() = items.rows
    val valueCount: Int get() = values.rows
}

/**
 * A way of working out the values of a facet an item should have from its text and its other
 * values. Learns from every item of the source: the values the site and the user gave them are
 * the examples, the user's word on a suggestion counts most.
 */
interface FacetSuggester {
    /** Stable: kept with the fitted state. */
    val id: String

    fun fit(task: SuggestionTask): FittedSuggester

    /** A state kept by [FittedSuggester.pack]. */
    fun unpack(bytes: ByteArray): FittedSuggester
}

interface FittedSuggester {
    /**
     * Readies the scoring of the items of [task] — of the task it was fitted on, or of a later one
     * of the same facet: what all the items share is worked out once here.
     */
    fun on(task: SuggestionTask): SuggestionScores

    fun pack(): ByteArray
}

fun interface SuggestionScores {
    /**
     * For each of [rows] (items of the task), the chance of every value — an array as long as the
     * task has values. A value the item has is judged as if the item did not say so: a low chance
     * marks a value the site gives it that does not fit.
     */
    fun of(rows: IntArray): List<FloatArray>
}
