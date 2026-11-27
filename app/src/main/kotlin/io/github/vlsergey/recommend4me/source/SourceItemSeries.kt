package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.series.SiteSeriesPageChangeEvent
import java.time.Instant
import kotlin.concurrent.withLock
import org.springframework.context.ApplicationEventPublisher

/**
 * The series of a source's items as the source's plugin writes them; the writes one at a time. A work is of one series
 * of a site: the one its facet of the series names — the first the site lists of it —, and a series' page lists of its
 * works those whose facet names it or nothing.
 */
internal class SourceItemSeries(private val store: SourceStore, private val events: ApplicationEventPublisher) :
    ItemSeriesStore {

    /** The source's facet of the series; none of a source without series. */
    private val facet = store.schema.facets.firstOrNull { it.role == FacetRole.SERIES }?.key

    override fun keepSeries(series: SeriesData) {
        val facet = facet ?: return
        store.writing.withLock {
            store.series.keep(series, Instant.now())
            series.title?.let { store.items.nameFacetValues(facet, mapOf(series.key to it)) }
            val listed = series.works.mapTo(HashSet()) { it.itemId }
            val own = store.items.facetsOf(listed)
            series.works.forEachIndexed { k, w ->
                val id = w.itemId
                val named = own[id]?.get(facet).orEmpty()
                // A work naming another series of the site is of that one: its own page's word
                if (named.isNotEmpty() && series.key !in named) return@forEachIndexed
                if (named.isEmpty()) store.items.setFacet(id, facet, listOf(FacetValue(series.key)))
                (w.position ?: if (series.whole) k + 1 else null)?.let { store.series.place(series.key, id, it) }
            }
            if (series.whole)
                store.items
                    .itemsWith(facet, listOf(series.key))[series.key]
                    .orEmpty()
                    .filter { it !in listed }
                    .forEach { id ->
                        store.items.setFacet(id, facet, emptyList())
                        store.series.unplace(series.key, id)
                    }
        }
        events.publishEvent(SiteSeriesPageChangeEvent(store.id, listOf(series.key)))
    }

    override fun placeInSeries(itemId: Long, key: String, position: Int) {
        if (facet == null) return
        store.writing.withLock { store.series.place(key, itemId, position) }
    }
}
