package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacetDefs

/**
 * THE SAID CASTS OF A SOURCE'S WORKS ([SaidCast]): of each, the entries of its line of characters and pairings the
 * source keeps — the values of its facet of the line — split by the source's own habit
 * ([Source.cast][io.github.vlsergey.recommend4me.source.Source.cast]).
 */
internal object WorkCasts {
    /** The source's facet of the entries of its line; null of a source without one. */
    fun facetOf(store: SourceStore): String? = UniverseFacetDefs.pairingsFacet(store.schema)

    /**
     * The cast of every work of [entries] (work to the keys of its entries, in the source's [facet]), their names read
     * once for all of them.
     */
    fun of(store: SourceStore, facet: String, entries: Map<String, List<String>>): Map<String, SaidCast> {
        val names = store.items.facetNames(facet, entries.values.flatMapTo(HashSet()) { it })
        return entries.mapValues { (_, keys) ->
            if (keys.isEmpty()) SaidCast.NONE else store.source.cast(keys.map { FacetValue(it, names[it]) })
        }
    }
}
