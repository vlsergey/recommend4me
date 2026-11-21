package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * WHAT THE USER SAID OF THE WORKS OF A SOURCE, read once for a pass over its facets and handed to every facet of it
 * ([FacetPass.said]): every answer on the values and every facet said all named ([answers]), and the works graded or
 * marked of interest ([rated]) — read of every work of the source once a pass, not two and three times a facet (owner,
 * 2026-11-21: "remove the repeats").
 */
internal class UserWord(val answers: Answers, val rated: Set<String>) {

    /**
     * The works the user answered on of the facet [facet] — a value said it has or has not, or its values said all
     * named: what its model learns of.
     */
    fun answered(facet: String): Set<String> {
        val said = answers.all.filter { it.facet == facet }.mapTo(HashSet()) { it.itemId }
        answers.complete.forEach { (id, facets) -> if (facet in facets) said += id }
        return said
    }

    companion object {
        /** What the user said of every work of the [store], read now. */
        fun of(store: SourceStore): UserWord =
            UserWord(
                Answers.of(store, null),
                store.ratings.all().mapTo(HashSet()) { it.itemId }.apply { addAll(store.interest.all().keys) },
            )
    }
}
