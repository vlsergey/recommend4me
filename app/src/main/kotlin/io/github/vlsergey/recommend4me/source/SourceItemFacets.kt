package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.item.SiteTagParentChangeEvent
import io.github.vlsergey.recommend4me.item.SiteTagSynonymChangeEvent
import io.github.vlsergey.recommend4me.universe.SiteFandomParentChangeEvent
import io.github.vlsergey.recommend4me.universe.SiteFandomSynonymChangeEvent
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import java.time.Instant
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

    override fun setFacet(itemId: Long, facet: String, values: List<FacetValue>) {
        write {
            // A work met as a work of a series it was not of: the series' page, read before, did not list it
            val newSeries =
                if (facet != seriesFacet) emptyList()
                else values.map { it.key } - store.items.facets(itemId)[facet].orEmpty().toSet()
            store.items.setFacet(itemId, facet, standardised(itemId, facet, values))
            store.series.worksSeen(newSeries, Instant.now())
        }
    }

    /** The source's facet of the series; none of a source without series. */
    private val seriesFacet = store.schema.facets.firstOrNull { it.role == FacetRole.SERIES }?.key

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

    override fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String> =
        store.items.facetDescriptions(facet, keys)

    /**
     * The synonyms written; of the fandoms, those told anew told on — bound to the universe of their fandom — of any
     * other facet, that its synonyms changed, with those told anew: the site's tags of them to bind.
     */
    override fun setFacetValueSynonyms(facet: String, synonyms: Map<String, Map<String, String>>, whole: Boolean) {
        if (synonyms.isEmpty()) return
        val told = write { store.items.setFacetValueSynonyms(facet, synonyms, whole) }
        if (facet != fandomFacet) events.publishEvent(SiteTagSynonymChangeEvent(store.id, facet, told))
        else if (told.isNotEmpty()) events.publishEvent(SiteFandomSynonymChangeEvent(store.id, told))
    }

    /** The parents written; those told anew told on: of the fandoms their universes, of the tags their tags related. */
    override fun setFacetValueParents(facet: String, parents: Map<String, Collection<String>>, whole: Boolean) {
        if (parents.isEmpty()) return
        val told = write { store.items.setFacetValueParents(facet, parents, whole) }
        if (told.isEmpty()) return
        events.publishEvent(
            if (facet == fandomFacet) SiteFandomParentChangeEvent(store.id, told)
            else SiteTagParentChangeEvent(store.id, facet, told)
        )
    }

    /** The source's facet of the sites' fandoms; none of a source without one. */
    private val fandomFacet = UniverseFacet.fandomFacet(store.schema)

    /**
     * The values of a standard facet with a closed list of them
     * ([StandardFacet][io.github.vlsergey.recommend4me.contenttype.StandardFacet]) named as the standard names them; a
     * value outside the list — one the source could not map — is left out and told of in the log. Of any other facet
     * the values as they are.
     */
    private fun standardised(itemId: Long, facet: String, values: List<FacetValue>): List<FacetValue> {
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

    override fun facets(itemId: Long): Map<String, List<String>> = store.items.facets(itemId)

    override fun forEachFacet(facet: String, action: (itemId: Long, keys: List<String>) -> Unit) =
        store.items.forEachFacet(facet, action)
}
