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
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE NORMALIZED SERIES AS THE SCREENS SHOW THEM: the list of a type's series ([page]), a series with what every site
 * says of it and its works in order ([one]) — the window of a series, and the work's own on the screen of a work
 * ([ofWork]).
 */
@Component
class SeriesViews(private val stores: Stores, private val lines: WorkLines) {

    /** A page of the series of the type a title of which holds [search], the most works first; null of no such type. */
    fun page(typeId: String, search: String?, offset: Int, limit: Int): SeriesPage? {
        val type = stores.type(typeId) ?: return null
        val all = TypeSeries.read(type).all
        val sites = sitesOf(type, all.flatMap { it.sites.entries }.associate { it.key to it.value.size })
        val untitled = all.filter { s -> s.sites.keys.none { sites[it]?.title != null } }
        val firstTitles = titlesOf(type, untitled)
        val rows =
            all.map { s ->
                    val own = s.sites.keys.mapNotNull { sites[it] }
                    SeriesRow(s.key, own.firstOrNull()?.title ?: firstTitles[s.key] ?: s.key, own, s.works.size)
                }
                .filter { row ->
                    search.isNullOrBlank() ||
                        row.title.contains(search, ignoreCase = true) ||
                        row.sites.any { it.title.contains(search, ignoreCase = true) }
                }
                .sortedWith(
                    compareByDescending<SeriesRow> { it.works }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
        return SeriesPage(rows.size, rows.drop(offset).take(limit))
    }

    /** The series of the [key] with its works; null of no such type or series. */
    fun one(typeId: String, key: String): SeriesDetails? {
        val type = stores.type(typeId) ?: return null
        val read = TypeSeries.read(type)
        return read.of(key)?.let { details(type, it) }
    }

    /** The series of the work of the items [members]: what its screen shows of it; none of a work of no series. */
    fun ofWork(type: TypeStore, members: List<ItemKey>): SeriesDetails? {
        val work = type.works.worksOf(members).values.firstOrNull() ?: return null
        return TypeSeries.around(type, listOf(work)).ofWork(work)?.let { details(type, it) }
    }

    /** The work of the item out of the series the user put it in; false of no such type or item. */
    fun takeOut(typeId: String, key: ItemKey): Boolean =
        stores.type(typeId)?.let { type ->
            type.works.worksOf(listOf(key))[key]?.let { work ->
                type.seriesGroups.leave(listOf(work))
                true
            }
        } ?: false

    /** What the screens show of the [series]: its sites, its works in order, each with its places. */
    private fun details(type: TypeStore, series: NormalizedSeries): SeriesDetails {
        val items = type.works.itemsOf(series.works)
        val sites = sitesOf(type, series.sites.mapValues { it.value.size })
        val placed = placesOf(type, series)
        val workOf = items.flatMap { (work, keys) -> keys.map { it to work } }.toMap()
        // A work is shown by an item of a series of a site of the series, or by its first
        val inSeries = series.sites.values.flatten().toSet()
        val shown = items.mapValues { (_, keys) -> keys.firstOrNull { it in inSeries } ?: keys.first() }
        val shownLines = linesOf(type, shown.values)
        val said = type.seriesGroups.groupsOf(series.works).keys
        val numbered =
            placed.values.map { places -> places.entries.mapNotNull { (k, p) -> workOf[k]?.let { it to p } }.toMap() }
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
            )
        }
        val own = series.sites.keys.mapNotNull { sites[it] }
        return SeriesDetails(
            series.key,
            own.firstOrNull()?.title ?: works.firstOrNull()?.work?.title ?: series.key,
            own,
            works,
        )
    }

    /**
     * The numbers of the works of the [series] in every series of a site of it that numbers them: of each, item to its
     * number — of the items of it alone, a work moved to another series of the site not.
     */
    private fun placesOf(type: TypeStore, series: NormalizedSeries): Map<SiteSeriesRef, Map<ItemKey, Int>> {
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

    /** Of the series of no series of a site, the title of a work of each: key to it. */
    private fun titlesOf(type: TypeStore, series: List<NormalizedSeries>): Map<String, String> {
        if (series.isEmpty()) return emptyMap()
        val items = type.works.itemsOf(series.mapNotNull { it.works.minOrNull() })
        val out = HashMap<String, String>()
        series.forEach { s ->
            val first = s.works.minOrNull()?.let { items[it]?.firstOrNull() } ?: return@forEach
            type.source(first.source)?.items?.find(first.id)?.let { out[s.key] = it.title }
        }
        return out
    }
}
