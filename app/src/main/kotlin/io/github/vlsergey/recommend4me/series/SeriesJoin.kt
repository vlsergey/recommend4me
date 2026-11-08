package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * THE JOINING OF WHAT A READING GATHERED INTO NORMALIZED SERIES: a series of a site ("ficbook/28510") and a group of
 * the user's ("said-12") are one series with every other one that shares a normalized work with them.
 */
internal class SeriesJoin(private val read: SeriesRead) {
    private val parent = HashMap<String, String>()

    private fun root(n: String): String {
        var r = n
        while (parent[r] != null && parent[r] != r) r = parent[r]!!
        parent[n] = r
        return r
    }

    private fun join(a: String, b: String) {
        val x = root(a)
        val y = root(b)
        if (x != y) parent[maxOf(x, y)] = minOf(x, y)
    }

    /** The normalized series read. */
    fun series(): List<NormalizedSeries> {
        // Every work's nodes: its items' series of their sites, its group
        val nodesOf = HashMap<Long, MutableSet<String>>()
        read.siteOf.forEach { (item, key) ->
            read.workOf[item]?.let { nodesOf.getOrPut(it) { HashSet() } += SiteSeriesRef(item.source, key).toString() }
        }
        read.groups.forEach { (group, works) ->
            works.forEach { nodesOf.getOrPut(it) { HashSet() } += "${TypeSeries.SAID}$group" }
        }
        nodesOf.values.forEach { nodes ->
            nodes.forEach { parent.putIfAbsent(it, it) }
            nodes.reduce { a, b -> a.also { join(a, b) } }
        }
        val worksOf = HashMap<String, MutableSet<Long>>()
        nodesOf.forEach { (work, nodes) -> worksOf.getOrPut(root(nodes.first())) { HashSet() } += work }
        val itemsOf = HashMap<String, MutableList<ItemKey>>()
        read.siteOf
            .filterKeys { it in read.workOf }
            .forEach { (item, key) ->
                itemsOf.getOrPut(SiteSeriesRef(item.source, key).toString()) { ArrayList() } += item
            }
        return parent.keys.groupBy(::root).map { (root, nodes) ->
            val sites = nodes.filter { !it.startsWith(TypeSeries.SAID) }.sorted()
            NormalizedSeries(
                sites.firstOrNull() ?: nodes.min(),
                sites.associate {
                    SiteSeriesRef(it.substringBefore('/'), it.substringAfter('/')) to itemsOf[it].orEmpty()
                },
                nodes
                    .filter { it.startsWith(TypeSeries.SAID) }
                    .map { it.removePrefix(TypeSeries.SAID).toLong() }
                    .toSet(),
                worksOf[root].orEmpty(),
            )
        }
    }
}
