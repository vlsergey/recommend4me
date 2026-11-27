package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore

/** A series of a site: the source and the site's key of it — "ficbook/28510". */
data class SiteSeriesRef(val source: String, val key: String) {
    override fun toString() = "$source/$key"
}

/**
 * A normalized series as read: its [key] — the smallest of its series of the sites, or of none the user's group
 * ("said-<id>") — the series of the sites it is made of with their items read ([sites]), the groups of the user's, and
 * its normalized works.
 */
class NormalizedSeries(
    val key: String,
    val sites: Map<SiteSeriesRef, List<ItemKey>>,
    val groups: Set<Long>,
    val works: Set<Long>,
    /**
     * Whether the user's groups hold it together: without them a work of it is in no series of a site, or its series of
     * the sites are not one. Of groups whose every word the sites' series say too, not.
     */
    val byHand: Boolean = false,
)

/** The source's facet of the series; none of a source without series. */
internal fun seriesFacetOf(s: SourceStore): String? = s.schema.facets.firstOrNull { it.role == FacetRole.SERIES }?.key

/**
 * THE JOIN OF THE SERIES AROUND A FEW WORKS, read by their keys: the series of their sites — a work's facet of the
 * series names its series of the site — and the works the user said to be one series ([SeriesGroups]), every one of
 * them sharing a normalized work with another one series with it ([SeriesJoin]), a work of one series at most. What the
 * normalized series kept are made of ([SeriesBinding]); of the sites' series alone, what holds works in one series
 * whatever the user says ([SeriesDifferences.heldBySites]).
 */
class TypeSeries
private constructor(
    /** Every series read. */
    val all: List<NormalizedSeries>
) {
    private val byWork = HashMap<Long, NormalizedSeries>().apply { all.forEach { s -> s.works.forEach { put(it, s) } } }

    /** The series of the work; none of a work of no series. */
    fun ofWork(work: Long): NormalizedSeries? = byWork[work]

    companion object {
        /** The prefix of the key of a group of the user's. */
        const val SAID = "said-"

        /**
         * The series of the [works] alone, read around them: their items' series of the sites, those series' works, the
         * user's groups of any of them, and so on, till no work is new. [bySites]: of the series of the sites alone, no
         * group of the user's — what holds works in one series whatever the user says.
         */
        fun around(type: TypeStore, works: Collection<Long>, bySites: Boolean = false): TypeSeries {
            val read = SeriesRead(type)
            val seenWorks = HashSet<Long>()
            val seenSeries = HashSet<SiteSeriesRef>()
            var fresh: Set<Long> = works.toSet()
            while (fresh.isNotEmpty()) {
                seenWorks += fresh
                val series = read.itemsOf(fresh) - seenSeries
                seenSeries += series
                fresh = (read.worksOf(series) + (if (bySites) emptySet() else read.groupsOf(fresh))) - seenWorks
            }
            return TypeSeries(SeriesJoin(read.siteOf, read.workOf, read.groups).series())
        }
    }
}
