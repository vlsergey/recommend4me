package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * WHAT THE LIST OF THE SERIES FILTERS AND ORDERS A SERIES BY, made of a join ([SeriesJoin]) as it is written
 * ([SeriesBinding]): its title — of its first series of a site, as its page or its works name it, or of its first work
 * —, every title it is known by, whether the user's word holds it together ([NormalizedSeries.byHand]), whether a page
 * of a series of a site of it is to be visited — never read, or read before a work of it was met elsewhere
 * ([SiteSeries.stale]). The pages and the names read of the series of the sites asked, by their keys.
 */
internal class SeriesColumns(private val type: TypeStore) {

    /** The series [s] as written, of the number [id] — none of a new one. */
    fun of(s: NormalizedSeries, id: Long?, worksChanged: Boolean): MadeSeries {
        val sites = s.sites.keys.sortedBy { it.toString() }
        val titles = LinkedHashMap<SiteSeriesRef, String>()
        var toVisit = false
        sites
            .groupBy { it.source }
            .forEach { (source, refs) ->
                val store = type.source(source) ?: return@forEach
                val keys = refs.map { it.key }
                val pages = store.series.of(keys)
                val names = seriesFacetOf(store)?.let { store.names(it, keys) }.orEmpty()
                refs.forEach { ref ->
                    val page = pages[ref.key]
                    if (page == null || page.stale) toVisit = true
                    (page?.title ?: names[ref.key])?.let { titles[ref] = it }
                }
            }
        val own = sites.firstNotNullOfOrNull(titles::get)
        val title = own ?: firstWorkTitle(s) ?: sites.firstOrNull()?.toString() ?: ""
        return MadeSeries(
            id = id,
            works = s.works,
            sites = s.sites.keys,
            title = title,
            titles = (listOf(title) + titles.values).distinct().joinToString("\n"),
            byHand = s.byHand,
            toVisit = toVisit,
            worksChanged = worksChanged,
        )
    }

    /** The title of the first work of the series [s] — of its smallest number —, as its first item names it. */
    private fun firstWorkTitle(s: NormalizedSeries): String? =
        s.works
            .minOrNull()
            ?.let { first -> type.works.itemsOf(listOf(first))[first]?.firstOrNull() }
            ?.let { item -> type.source(item.source)?.items?.find(item.id)?.title }
}
