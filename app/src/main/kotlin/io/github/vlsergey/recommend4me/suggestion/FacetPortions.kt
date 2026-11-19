package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE WORKS OF A FACET'S PASS AS ITS SUGGESTER FITS ON THEM ([TaskPortions]): the [side] of its values alike for every
 * portion, every portion read anew of the database at every walk ([FacetPass.forEach]) — its works, their inputs on the
 * card, their task of the value side — and let go before the next. Of the [works] the user answered on alone: the model
 * learns of the user's word, and a work the user said nothing of teaches it nothing (user, 2026-11-19).
 *
 * Of the works the user answered on, what the user said of them, as the walks read them ([answered]): what the
 * calibration of the facet is learnt of — a few works, never every one.
 */
internal class FacetPortions(
    private val pass: FacetPass,
    private val side: ValueSide,
    /** The works the model learns of: those the user answered on of the facet ([answered]). */
    private val works: Collection<String>,
) : TaskPortions {
    override val items: Int = works.size

    override val values: SuggestionTask = side.task()

    /**
     * What the user said of each work answered on of the facet: the values it has ([yes]) and has not ([no]); of a work
     * said all named, the values it has ([own]) — every other a no.
     */
    private class Said(val yes: IntArray, val no: IntArray, val own: IntArray?) {
        /** Whether the user said the work has the value [v]: null of a value the user said nothing of. */
        fun of(v: Int): Boolean? =
            when {
                v in yes -> true
                own?.let { v !in it } ?: (v in no) -> false
                else -> null
            }
    }

    /** What the user said of the works answered on, by the work's id, as the last walk read them. */
    private val said = HashMap<String, Said>()

    override fun forEach(action: (SuggestionTask) -> Unit) =
        pass.forEach(inputs = true, only = works) { p ->
            val data = pass.data(p, side)
            remember(data)
            action(data.task)
        }

    /** What the user said of the works of a portion [data] answered on. */
    private fun remember(data: FacetData) {
        data.ids.forEachIndexed { i, id ->
            val yes = data.saidYes[i]
            val complete = data.complete[i]
            val no = if (complete) IntArray(0) else data.saidNo[i]
            if (yes.isNotEmpty() || no.isNotEmpty() || complete)
                said[id] = Said(yes, no, if (complete) data.task.assigned[i] else null)
            else said.remove(id)
        }
    }

    /**
     * Of what the folds of a fitting said of the values the user answered on ([held]), the answers the calibration is
     * learnt of ([Calibration]): a yes of a value the user said a work has, a no of one the user said it has not, of a
     * work all named every value it has not — not spread over the values' hierarchy.
     */
    fun answered(held: HeldOutScores): List<Answered> =
        held.items.indices.mapNotNull { k ->
            val v = held.values[k]
            said[held.items[k]]?.of(v)?.let { yes -> Answered(side.values[v], held.scores[k], yes) }
        }

    companion object {
        /**
         * The works of the [store] the user answered on of the facet [facet] — a value said it has or has not, or its
         * values said all named: what its model learns of.
         */
        fun answered(store: SourceStore, facet: String): Set<String> {
            val said = store.corrections.allFacets().filter { it.facet == facet }.mapTo(HashSet()) { it.itemId }
            store.corrections.allComplete().forEach { (id, facets) -> if (facet in facets) said += id }
            return said
        }
    }
}
