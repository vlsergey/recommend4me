package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix

/**
 * One view of the items' texts: a vector per item, of unit length, a row of zeros for an item
 * without it — the description, the chapters read, the facet's values as the site writes them.
 *
 * [ofValues]: the view is the item's own values of the facet as the site writes them. Its likeness
 * to a value's name would only repeat what the site gave the item; only what the items alike in
 * it have says something.
 */
class TextView(val name: String, val vectors: Matrix, val ofValues: Boolean = false)

/**
 * A way the values of a facet fall into groups alike in what goes with them — the pairings of two
 * men, of two women, of a man and a woman; the characters of either sex. What is learnt of a group
 * holds for every value in it, a value never seen with a work's tags too. [groupOf]: the group of
 * every value of the task, -1 for a value of no known group.
 */
class ValueGrouping(val name: String, val groupOf: IntArray, val groupCount: Int)

/**
 * Everything a suggester knows of one facet of one source ([FacetDef.suggest][io.github.vlsergey.recommend4me.source.FacetDef.suggest],
 * [FacetDef.infer][io.github.vlsergey.recommend4me.source.FacetDef.infer]): every item — its texts
 * in several views, the facet's values it has, the values of its other facets — and every value
 * that may be suggested, with the vector of its name.
 *
 * Rows of the views and of the lists are items, rows of [values] values; [assigned], [confirmed]
 * and [rejected] hold indices of [values], [context] indices of the [contextCount] values of the
 * other facets.
 */
class SuggestionTask(
    val views: List<TextView>,
    /** One row per value: the vector of its name as a query, of unit length; zeros when not encoded. */
    val values: Matrix,
    /** The values each item has now: the site's, with the user's corrections applied. */
    val assigned: List<IntArray>,
    /** The values the user said the item has: added by hand or accepted when suggested. */
    val confirmed: List<IntArray>,
    /** The values the user said the item does not have: taken from it, or rejected when suggested. */
    val rejected: List<IntArray>,
    /** The values of the item's other facets — its fandom, its tags, its author. */
    val context: List<IntArray>,
    val contextCount: Int,
    /**
     * How many times the item's own texts name each value — a character mentioned in the chapters,
     * two named in one paragraph: value index to count, the values not named left out; null for an
     * item whose texts were not counted, an empty map for one that names none.
     */
    val mentions: List<Map<Int, Int>?> = List(assigned.size) { null },
    /**
     * The values each item may have at all — a work's characters are of its universes — null for
     * an item that may have any. A value outside them is no example of the item: not one it lacks,
     * one it cannot have; its chance is still worked out.
     */
    val allowed: List<IntArray?> = List(assigned.size) { null },
    /** The groupings of the values, none for a facet whose values are not known to fall into groups. */
    val groupings: List<ValueGrouping> = emptyList(),
) {
    val itemCount: Int get() = assigned.size
    val valueCount: Int get() = values.rows

    init {
        require(views.isNotEmpty()) { "No view of the texts" }
        require(views.all { it.vectors.rows == itemCount && it.vectors.cols == values.cols }) {
            "Views of ${views.map { "${it.vectors.rows}×${it.vectors.cols}" }}, $itemCount items, values of ${values.cols}"
        }
        require(
            confirmed.size == itemCount && rejected.size == itemCount && context.size == itemCount && mentions.size == itemCount && allowed.size == itemCount,
        ) {
            "$itemCount assigned, ${confirmed.size} confirmed, ${rejected.size} rejected, ${context.size} contexts, ${mentions.size} mentions, ${allowed.size} allowed"
        }
        require(groupings.all { g -> g.groupOf.size == valueCount && g.groupOf.all { it in -1 until g.groupCount } }) { "A grouping not of the $valueCount values" }
    }
}

/**
 * A way of working out the values of a facet an item should have from its texts and its other
 * values. Learns from every item of the source: the values the site and the user gave them are
 * the examples. What the user said of a work is the application's to apply over the chances.
 */
interface FacetSuggester {
    /** Stable: kept with the fitted state. */
    val id: String

    /** Learns from every item of [task]; null when there is nothing to learn from — no item has a value, or every item has all. */
    fun fit(task: SuggestionTask): FittedSuggester?

    /** A state kept by [FittedSuggester.pack]; null when it does not fit tasks of [task]'s shape — it is fitted again. */
    fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester?
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
