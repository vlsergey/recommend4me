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
 * THE NORMALIZED SERIES OF A CONTENT TYPE, made on reading of the series of its sites — a work's facet of the series
 * names its series of the site — and of the works the user said to be one series ([SeriesGroups]): every one of them
 * sharing a normalized work with another is one series with it ([SeriesJoin]), a work of one series at most.
 */
class TypeSeries
private constructor(
    /** Every series read. */
    val all: List<NormalizedSeries>,
    /** The normalized work of every item read. */
    private val workOf: Map<ItemKey, Long>,
) {
    private val byWork = HashMap<Long, NormalizedSeries>().apply { all.forEach { s -> s.works.forEach { put(it, s) } } }

    private val byKey = all.associateBy { it.key }

    /** The series of the work; none of a work of no series. */
    fun ofWork(work: Long): NormalizedSeries? = byWork[work]

    /**
     * The series of the [key] — its own, or of a series of a site or a group of the user's it is made of —; none when
     * there is no such series.
     */
    fun of(key: String): NormalizedSeries? =
        byKey[key]
            ?: all.firstOrNull { s -> s.sites.keys.any { it.toString() == key } || s.groups.any { "$SAID$it" == key } }

    /** The key of the series of the item's work; none of an item of no series read. */
    fun keyOf(key: ItemKey): String? = workOf[key]?.let(byWork::get)?.key

    companion object {
        /** The prefix of the key of a group of the user's. */
        const val SAID = "said-"

        /** Every series of the [type]: every source's facet of the series read once, and every group of the user's. */
        fun read(type: TypeStore): TypeSeries {
            val read =
                SeriesRead(type).apply {
                    everySite()
                    everyGroup()
                }
            return TypeSeries(SeriesJoin(read.siteOf, read.workOf, read.groups).series(), read.workOf)
        }

        /**
         * The series of the [works] alone, read around them: their items' series of the sites, those series' works, the
         * user's groups of any of them, and so on, till no work is new.
         */
        fun around(type: TypeStore, works: Collection<Long>): TypeSeries {
            val read = SeriesRead(type)
            val seenWorks = HashSet<Long>()
            val seenSeries = HashSet<SiteSeriesRef>()
            var fresh: Set<Long> = works.toSet()
            while (fresh.isNotEmpty()) {
                seenWorks += fresh
                val series = read.itemsOf(fresh) - seenSeries
                seenSeries += series
                fresh = (read.worksOf(series) + read.groupsOf(fresh)) - seenWorks
            }
            return TypeSeries(SeriesJoin(read.siteOf, read.workOf, read.groups).series(), read.workOf)
        }
    }
}
