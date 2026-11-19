package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.api.model.SeriesRow

/**
 * WHICH SERIES THE LIST SHOWS: those a title of which — its own or of a series of a site of it — holds [search], with a
 * series of every source of [with] and of none of [without], and — of [said] given — held together by the user's word
 * that works are one series ([NormalizedSeries.byHand]), or not; of [toVisit] given, with a series of a site whose page
 * is to be visited — never read, or read before a work of it was met elsewhere ([SiteSeries.stale]) — or with none.
 * Nothing given, every series.
 */
class SeriesFilter(
    val search: String? = null,
    val with: Collection<String> = emptyList(),
    val without: Collection<String> = emptyList(),
    val said: Boolean? = null,
    val toVisit: Boolean? = null,
) {
    /** Whether the [series] is let by its sites and the user's word. */
    fun lets(series: NormalizedSeries): Boolean {
        val sources = series.sites.keys.map { it.source }.toSet()
        return sources.containsAll(with) && without.none { it in sources } && (said == null || said == series.byHand)
    }

    /** Whether the [row] is let by its titles and by whether a page of it is to be visited. */
    fun lets(row: SeriesRow): Boolean =
        (toVisit == null || toVisit == row.sites.any { !it.seen || it.stale }) &&
            (search.isNullOrBlank() ||
                row.title.contains(search, ignoreCase = true) ||
                row.sites.any { it.title.contains(search, ignoreCase = true) })
}
