package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * A FACET OF THE LAYER WORKED OUT BY A MODEL OF ITS OWN, not by the facet's suggester of every
 * source apart: the age rating, learnt from every site's works at once ([AgeRatings][io.github.vlsergey.recommend4me.agerating.AgeRatings]).
 * Its chances are among every work's as the drafts are — made before the chain of the facets, read by
 * the card, the filters and every facet after — and the chain fits no suggester of it.
 */
interface FacetModel {
    /** The key of the facet of the layer it works out. */
    val facet: String

    /** The chances of the [facet] of the works [ids] of the source — every work when null — by item, then by value; none of a work it tells nothing of. */
    fun chances(store: SourceStore, ids: Collection<String>?): Map<String, Map<String, Chance>>
}
