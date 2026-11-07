package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.item.SynonymsSet
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

private val log = LoggerFactory.getLogger(SourceItemFacets::class.java)

/**
 * The facets of a source's items and the names of their values, as the source's plugin writes and reads them; the
 * writes one at a time ([SourceStore.writing]).
 */
internal class SourceItemFacets(private val store: SourceStore, private val events: ApplicationEventPublisher) :
    ItemFacetStore {
    private fun <T> write(block: () -> T): T = store.writing.withLock(block)

    override fun setFacet(itemId: String, facet: String, values: List<FacetValue>) = write {
        store.items.setFacet(itemId, facet, standardised(itemId, facet, values))
    }

    override fun nameFacetValues(facet: String, names: Map<String, String>) = write {
        val standard = store.schema.facet(facet)?.standard
        store.items.nameFacetValues(
            facet,
            if (standard?.values == null) names
            else names.mapValues { (key, name) -> standard.value(key)?.modelName ?: name },
        )
    }

    override fun describeFacetValues(facet: String, descriptions: Map<String, String>) = write {
        store.items.describeFacetValues(facet, descriptions)
    }

    override fun setFacetValueSynonyms(facet: String, synonyms: Map<String, Map<String, String>>) {
        if (synonyms.isEmpty()) return
        write { store.items.setFacetValueSynonyms(facet, synonyms) }
        events.publishEvent(SynonymsSet(store.id, facet))
    }

    /**
     * The values of a standard facet with a closed list of them
     * ([StandardFacet][io.github.vlsergey.recommend4me.contenttype.StandardFacet]) named as the standard names them; a
     * value outside the list — one the source could not map — is left out and told of in the log. Of any other facet
     * the values as they are.
     */
    private fun standardised(itemId: String, facet: String, values: List<FacetValue>): List<FacetValue> {
        val standard = store.schema.facet(facet)?.standard?.takeIf { it.values != null } ?: return values
        return values.mapNotNull { v ->
            val known = standard.value(v.key)
            if (known == null)
                log.warn(
                    "{}: «{}» of item {} is no value of the standard facet {}: left out",
                    store.id,
                    v.name ?: v.key,
                    itemId,
                    standard.key,
                )
            // Kept in the language the model reads ([StandardValue.modelName]); shown by its key
            // ([SourceStore.nameKey])
            known?.let { FacetValue(it.key, it.modelName) }
        }
    }

    override fun facets(itemId: String): Map<String, List<String>> = store.items.facets(itemId)

    override fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) =
        store.items.forEachFacet(facet, action)
}
