package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * WHAT A READING OF THE SERIES GATHERED: every item's series of its site ([siteOf]), every item's normalized work
 * ([workOf]), the user's groups ([groups]) — read of a whole type or around a few works, step by step.
 */
internal class SeriesRead(private val type: TypeStore) {
    val siteOf = HashMap<ItemKey, String>()
    val workOf = HashMap<ItemKey, Long>()
    val groups = HashMap<Long, Set<Long>>()

    /** Every item of every series of every site, with its work. */
    fun everySite() {
        type.sources.forEach { s ->
            val facet = seriesFacetOf(s) ?: return@forEach
            val ids = ArrayList<String>()
            s.items.forEachFacet(facet) { id, keys ->
                keys.firstOrNull()?.let {
                    siteOf[ItemKey(s.id, id)] = it
                    ids += id
                }
            }
            s.works.ofItems(s.id, ids).forEach { (id, work) -> workOf[ItemKey(s.id, id)] = work }
        }
    }

    /** Every group of the user's, with the items of its works. */
    fun everyGroup() {
        groups += type.seriesGroups.all()
        type.works.itemsOf(groups.values.flatten()).forEach { (work, items) -> items.forEach { workOf[it] = work } }
    }

    /** The items of the [works] and their series of their sites: the series. */
    fun itemsOf(works: Collection<Long>): Set<SiteSeriesRef> {
        val items = type.works.itemsOf(works)
        items.forEach { (work, keys) -> keys.forEach { workOf[it] = work } }
        val series = HashSet<SiteSeriesRef>()
        items.values
            .flatten()
            .groupBy { it.source }
            .forEach { (source, keys) ->
                val s = type.source(source) ?: return@forEach
                val facet = seriesFacetOf(s) ?: return@forEach
                s.items.facetsOf(keys.map { it.id }).forEach { (id, facets) ->
                    facets[facet]?.firstOrNull()?.let {
                        siteOf[ItemKey(source, id)] = it
                        series += SiteSeriesRef(source, it)
                    }
                }
            }
        return series
    }

    /** Every item of the series of the sites [refs], with its work: their works. */
    fun worksOf(refs: Collection<SiteSeriesRef>): Set<Long> {
        val found = HashSet<Long>()
        refs
            .groupBy { it.source }
            .forEach { (source, of) ->
                val s = type.source(source) ?: return@forEach
                val facet = seriesFacetOf(s) ?: return@forEach
                val members = s.items.itemsWith(facet, of.map { it.key })
                members.forEach { (key, ids) -> ids.forEach { siteOf.putIfAbsent(ItemKey(source, it), key) } }
                s.works.ofItems(source, members.values.flatten()).forEach { (id, work) ->
                    workOf[ItemKey(source, id)] = work
                    found += work
                }
            }
        return found
    }

    /** The user's groups of the [works] not read yet, with their works: those works. */
    fun groupsOf(works: Collection<Long>): Set<Long> {
        val of = type.seriesGroups.groupsOf(works).values.toSet() - groups.keys
        val found = HashSet<Long>()
        type.seriesGroups.worksOf(of).forEach { (group, members) ->
            groups[group] = members
            found += members
        }
        return found
    }
}
