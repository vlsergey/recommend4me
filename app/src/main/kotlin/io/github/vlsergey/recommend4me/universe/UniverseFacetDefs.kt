package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * THE FACETS OF THE LAYER OF UNIVERSES IN A SOURCE'S SCHEMA ([UniverseFacet]): their definitions, and the site's own
 * facets that speak of each, shown right above it.
 */
object UniverseFacetDefs {

    /**
     * The facets of a source of a type with universes, each with the site's own word on it shown right above it: the
     * site's fandoms above the universes, its line of pairings and characters above the characters (the pairings come
     * right after them).
     */
    fun defs(schema: SourceSchema): List<FacetDef> {
        fun keyOf(standard: Any) = schema.facets.firstOrNull { it.standard === standard }?.key
        return listOf(
            // The site may say it, but it is the layer's: the user's answer is the work's kind. The
            // site's facet it is told by is shown above it
            UniverseFacets.KIND_FACET.asLayerFacet(examplesFrom = null, originalFacet = schema.kindFrom),
            FacetDef(
                UniverseFacet.KEY,
                filter = true,
                // One facet across the sources of the type: a universe is one whichever site a work is from
                shared = UniverseFacet.KEY,
                searchWeight = 0.9f,
                onCard = true,
                infer = true,
                editable = true,
                originalFacet = UniverseFacet.fandomFacet(schema),
            ),
            FacetDef(
                UniverseFacets.CHARACTERS,
                shared = UniverseFacets.CHARACTERS,
                searchWeight = 0.8f,
                infer = true,
                original = schema.universeLine,
                editable = true,
                originalFacet = keyOf(UniverseFacets.PAIRINGS_FACET),
            ),
            // After the main ones: what the work has of those is left out of these
            FacetDef(
                UniverseFacets.SECONDARY_CHARACTERS,
                shared = UniverseFacets.SECONDARY_CHARACTERS,
                searchWeight = 0.6f,
                infer = true,
                original = schema.universeLine,
                editable = true,
            ),
            FacetDef(
                UniverseFacets.PAIRINGS,
                shared = UniverseFacets.PAIRINGS,
                searchWeight = 0.7f,
                infer = true,
                original = schema.universeLine,
                editable = true,
            ),
        )
    }

    /**
     * The site's facets that speak of a field ([def], a facet of the layer), shown above it: its
     * [FacetDef.originalFacet]; of the universes, a site with no fandoms the facet it tells the kind by, a fandom being
     * among author.today's tags; of the secondary characters and the pairings the line of the characters too, each
     * field having it in sight; of the pairings their direction.
     */
    fun saidBy(def: FacetDef, schema: SourceSchema): List<String> =
        when (def.key) {
            UniverseFacet.KEY -> listOfNotNull(UniverseFacet.fandomFacet(schema) ?: schema.kindFrom)
            UniverseFacets.CHARACTERS,
            UniverseFacets.SECONDARY_CHARACTERS -> listOfNotNull(pairingsFacet(schema))
            UniverseFacets.PAIRINGS ->
                listOfNotNull(pairingsFacet(schema), schema.facets.firstOrNull { it.standard === Books.DIRECTION }?.key)
            else -> listOfNotNull(def.originalFacet)
        }

    /** The source's facet of the site's line of characters and pairings, word by word; null when it has none. */
    fun pairingsFacet(schema: SourceSchema): String? =
        schema.facets.firstOrNull { it.standard === UniverseFacets.PAIRINGS_FACET }?.key
}
