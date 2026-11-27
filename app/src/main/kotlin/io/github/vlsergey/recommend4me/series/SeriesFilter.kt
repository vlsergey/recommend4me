package io.github.vlsergey.recommend4me.series

/**
 * WHICH SERIES THE LIST SHOWS: those a title of which — its own or of a series of a site of it — holds [search], with a
 * series of every source of [with] and of none of [without], and — of [said] given — held together by the user's word
 * that works are one series ([NormalizedSeries.byHand]), or not; of [toVisit] given, with a series of a site whose page
 * is to be visited — never read, or read before a work of it was met elsewhere ([SiteSeries.stale]) — or with none;
 * with series of [minSites] sites at least. Nothing given, every series. Chosen in the database, by the columns kept of
 * every series ([NormalizedSeriesRepository.page]).
 */
class SeriesFilter(
    val search: String? = null,
    val with: Collection<String> = emptyList(),
    val without: Collection<String> = emptyList(),
    val said: Boolean? = null,
    val toVisit: Boolean? = null,
    val minSites: Int = 0,
)
