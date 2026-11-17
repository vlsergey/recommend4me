package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE VALUES OF THE FACETS OF EVERY ITEM OF A SOURCE, read once by a reading of every work: the site's values by item
 * and facet ([site]), the user's answers by item ([corrections]). Handed to every part of the same reading that reads
 * them — the catalogue, the chain of the facets, the models of facets of their own — not read again by each: of tens of
 * thousands of works a second and more a reading. Lives as long as the reading.
 */
class EveryItemFacets(
    val site: Map<String, Map<String, List<String>>>,
    val corrections: Map<String, List<FacetCorrection>>,
) {
    /** The user's answers of every item in one list. */
    val allCorrections: List<FacetCorrection> by lazy { corrections.values.flatten() }

    companion object {
        /**
         * Every value of every facet of every item of the [store], by item and facet: one pass over its table, a work's
         * facets in arrays ([WorkFacets]).
         */
        fun siteOf(store: SourceStore): Map<String, Map<String, List<String>>> {
            val reading = WorkFacets.Reading()
            store.items.forEachFacetValue(reading::add)
            return reading.done()
        }
    }
}
