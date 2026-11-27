package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.api.model.RelatedWork
import io.github.vlsergey.recommend4me.api.model.SeriesDetails
import io.github.vlsergey.recommend4me.api.model.SeriesPage
import io.github.vlsergey.recommend4me.api.model.SeriesPlace
import io.github.vlsergey.recommend4me.api.model.SeriesRow
import io.github.vlsergey.recommend4me.api.model.SeriesSite
import io.github.vlsergey.recommend4me.api.model.SeriesWork
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.WorkLines
import io.github.vlsergey.recommend4me.size.ItemSize
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE NORMALIZED SERIES AS THE SCREENS SHOW THEM: the list of a type's series ([page]) — a page chosen in the database
 * ([NormalizedSeriesRepository.page]) —, a series with what every site says of it and its works in order ([one]) — the
 * window of a series, and the work's own on the screen of a work ([ofWork]) —, read by their numbers.
 */
@Component
class SeriesViews(private val stores: Stores, private val lines: WorkLines, private val sizes: WorkSizes) {

    /** A page of the series of the type the [filter] lets, the most works first; null of no such type. */
    fun page(typeId: String, filter: SeriesFilter, offset: Int, limit: Int): SeriesPage? {
        val type = stores.type(typeId) ?: return null
        val (total, entries) = type.series.list.page(filter, offset, limit)
        val ofSeries = type.series.sitesOf(entries.map { it.id })
        val sites = sitesOf(type, worksOfSites(type, ofSeries.values.flatten()))
        val rows = entries.map { e ->
            SeriesRow(
                id = e.id,
                title = e.title,
                sites = ofSeries[e.id].orEmpty().sortedBy { it.toString() }.mapNotNull { sites[it] },
                works = e.works,
                said = e.byHand,
            )
        }
        return SeriesPage(total, rows)
    }

    /** The series [id] with its works; null of no such type or series. */
    fun one(typeId: String, id: Long): SeriesDetails? {
        val type = stores.type(typeId) ?: return null
        return stored(type, id)?.let { details(type, it) }
    }

    /** The series of the work of the items [members]: what its screen shows of it; none of a work of no series. */
    fun ofWork(type: TypeStore, members: List<ItemKey>): SeriesDetails? {
        val work = type.works.worksOf(members).values.firstOrNull() ?: return null
        return type.series.ofWorks(listOf(work))[work]?.let { stored(type, it) }?.let { details(type, it) }
    }

    /** The series [id] as kept, with the items of each of its series of the sites; none of no such series. */
    private fun stored(type: TypeStore, id: Long): StoredSeries? {
        val entry = type.series.list.entries(listOf(id))[id] ?: return null
        val refs = type.series.sitesOf(listOf(id))[id].orEmpty()
        val items = HashMap<SiteSeriesRef, List<ItemKey>>()
        refs
            .groupBy { it.source }
            .forEach { (source, of) ->
                val s = type.source(source) ?: return@forEach
                val facet = seriesFacetOf(s) ?: return@forEach
                s.items.itemsWith(facet, of.map { it.key }).forEach { (key, ids) ->
                    items[SiteSeriesRef(source, key)] = ids.map { ItemKey(source, it) }
                }
            }
        return StoredSeries(
            entry,
            type.series.worksOf(listOf(id))[id].orEmpty(),
            refs.sortedBy { it.toString() }.associateWith { items[it].orEmpty() },
        )
    }

    /** How many works every series of a site of [refs] has, as its works name it. */
    private fun worksOfSites(type: TypeStore, refs: Collection<SiteSeriesRef>): Map<SiteSeriesRef, Int> =
        refs
            .groupBy { it.source }
            .flatMap { (source, of) ->
                val s = type.source(source) ?: return@flatMap emptyList()
                val facet = seriesFacetOf(s) ?: return@flatMap emptyList()
                val uses = s.items.valueUsesOf(facet, of.map { it.key })
                of.map { it to (uses[it.key] ?: 0) }
            }
            .toMap()

    /** The work of the item out of the series the user put it in; false of no such type or item. */
    fun takeOut(typeId: String, key: ItemKey): Boolean =
        stores.type(typeId)?.let { type ->
            type.works.worksOf(listOf(key))[key]?.let { work ->
                type.seriesGroups.leave(listOf(work))
                true
            }
        } ?: false

    /** What the screens show of the [series]: its sites, its works in order, each with its places. */
    private fun details(type: TypeStore, series: StoredSeries): SeriesDetails {
        val items = type.works.itemsOf(series.works)
        val sites = sitesOf(type, series.sites.mapValues { it.value.size })
        val placed = placesOf(type, series)
        val workOf = items.flatMap { (work, keys) -> keys.map { it to work } }.toMap()
        // A work is shown by an item of a series of a site of the series, or by its first
        val inSeries = series.sites.values.flatten().toSet()
        val shown = items.mapValues { (_, keys) -> keys.firstOrNull { it in inSeries } ?: keys.first() }
        val shownLines = linesOf(type, shown.values)
        val said = type.seriesGroups.groupsOf(series.works).keys
        val all = items.values.flatten()
        val facts = SeriesItemFacts(type, all, sizes)
        val sized = facts.sizes
        val published = facts.firstPublished
        val volumes = volumesOf(series, sized)
        val numbered = placed.map { (ref, places) ->
            SiteOrder(
                places.entries.mapNotNull { (k, p) -> workOf[k]?.let { it to p } }.toMap(),
                series.sites[ref].orEmpty().mapNotNull(workOf::get).distinct().size,
                volumes[ref] ?: 0.0,
            )
        }
        val titleOf = { work: Long -> shown[work]?.let { shownLines[it]?.title }.orEmpty() }
        val order = SeriesOrder.of(series.works, numbered, compareBy(String.CASE_INSENSITIVE_ORDER, titleOf))
        val works = order.mapNotNull { work ->
            val line = shown[work]?.let { shownLines[it] } ?: return@mapNotNull null
            val places =
                placed.entries.mapNotNull { (ref, of) ->
                    items[work].orEmpty().firstNotNullOfOrNull { of[it] }?.let { SeriesPlace(ref.source, it) }
                }
            val own = items[work].orEmpty()
            SeriesWork(
                work = line,
                sources = own.map { it.source }.distinct(),
                places = places,
                said = work in said,
                bySite = own.any { it in inSeries },
                // Its first chapter of any of its sites; its size of the item it is shown by, else of any that tells
                firstPublishedAt = own.mapNotNull { published[it] }.minOrNull()?.atOffset(ZoneOffset.UTC),
                words = (listOfNotNull(shown[work]) + own).firstNotNullOfOrNull { sized[it]?.words },
            )
        }
        return SeriesDetails(series.entry.id, series.entry.title, series.sites.keys.mapNotNull { sites[it] }, works)
    }

    /**
     * The numbers of the works of the [series] in every series of a site of it that numbers them: of each, item to its
     * number — of the items of it alone, a work moved to another series of the site not.
     */
    private fun placesOf(type: TypeStore, series: StoredSeries): Map<SiteSeriesRef, Map<ItemKey, Int>> {
        val placed = HashMap<SiteSeriesRef, Map<ItemKey, Int>>()
        series.sites.keys
            .groupBy { it.source }
            .forEach { (source, refs) ->
                val s = type.source(source) ?: return@forEach
                s.series.placesOf(refs.map { it.key }).forEach { (key, places) ->
                    val ref = SiteSeriesRef(source, key)
                    val members = series.sites[ref].orEmpty().toSet()
                    placed[ref] = places.mapKeys { ItemKey(source, it.key) }.filterKeys { it in members }
                }
            }
        return placed
    }

    /**
     * The volume of every series of a site of the [series]: the words of its works, or of a site that states none the
     * characters — what tells, of two sites of as many works, whose order goes first ([SeriesOrder]).
     */
    private fun volumesOf(series: StoredSeries, sized: Map<ItemKey, ItemSize>): Map<SiteSeriesRef, Double> =
        series.sites.mapValues { (_, items) -> items.sumOf { (sized[it]?.words ?: sized[it]?.chars)?.value ?: 0.0 } }

    /** The [items] as lines of a list: item to its line. */
    private fun linesOf(type: TypeStore, items: Collection<ItemKey>): Map<ItemKey, RelatedWork> {
        val out = HashMap<ItemKey, RelatedWork>()
        items
            .groupBy { it.source }
            .forEach { (source, keys) ->
                val s = type.source(source) ?: return@forEach
                lines.of(type, s, keys.map { it.id }).forEach { (id, line) -> out[ItemKey(source, id)] = line }
            }
        return out
    }

    /**
     * What every site says of the series [refs], with the number of works it names of each: what their pages said, the
     * title the works name them by of a series whose page was never seen.
     */
    private fun sitesOf(type: TypeStore, refs: Map<SiteSeriesRef, Int>): Map<SiteSeriesRef, SeriesSite> {
        val out = HashMap<SiteSeriesRef, SeriesSite>()
        val status = type.type.facets.firstOrNull { it.role == FacetRole.STATUS }
        refs.keys
            .groupBy { it.source }
            .forEach { (source, of) ->
                val s = type.source(source) ?: return@forEach
                val facet = seriesFacetOf(s) ?: return@forEach
                val keys = of.map { it.key }
                val pages = s.series.of(keys)
                val names = s.names(facet, keys)
                of.forEach { ref ->
                    val page = pages[ref.key]
                    out[ref] =
                        SeriesSite(
                            source = source,
                            key = ref.key,
                            title = page?.title ?: names[ref.key] ?: ref.key,
                            seen = page != null,
                            stale = page?.stale == true,
                            works = refs.getValue(ref),
                            // Of a series whose page was never seen, its page as the source makes it of the key
                            url = page?.url ?: s.source.valueUrl(facet, ref.key, names[ref.key] ?: ref.key),
                            description = page?.description,
                            status = page?.status,
                            statusKey = page?.status?.let { st -> status?.let { I18nKeys.standardValueOf(it, st) } },
                            begunAt = page?.begunAt?.atOffset(ZoneOffset.UTC),
                            updatedAt = page?.updatedAt?.atOffset(ZoneOffset.UTC),
                        )
                }
            }
        return out
    }
}

/** A normalized series as kept ([entry]), its [works] and its series of the sites with their items. */
private class StoredSeries(val entry: SeriesEntry, val works: Set<Long>, val sites: Map<SiteSeriesRef, List<ItemKey>>)
