package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * THE JOINING OF WHAT A READING GATHERED INTO NORMALIZED SERIES: a series of a site ("ficbook/28510") and a group of
 * the user's ("said-12") are one series with every other one that shares a normalized work with them — of every item's
 * series of its site ([siteOf]), every item's normalized work ([workOf]) and the user's groups ([groups]).
 */
internal class SeriesJoin(
    private val siteOf: Map<ItemKey, String>,
    private val workOf: Map<ItemKey, Long>,
    private val groups: Map<Long, Set<Long>>,
) {

    /** The series of the sites and the user's groups joined into one: each node's root, the smallest of its own. */
    private class Joined {
        private val parent = HashMap<String, String>()

        val nodes: Set<String>
            get() = parent.keys

        fun root(n: String): String {
            var r = n
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            parent[n] = r
            return r
        }

        /** The [nodes] of one work, one series. */
        fun joinAll(nodes: Collection<String>) {
            nodes.forEach { parent.putIfAbsent(it, it) }
            nodes.reduce { a, b -> a.also { join(a, b) } }
        }

        private fun join(a: String, b: String) {
            val x = root(a)
            val y = root(b)
            if (x != y) parent[maxOf(x, y)] = minOf(x, y)
        }
    }

    /** The normalized series read. */
    fun series(): List<NormalizedSeries> {
        // Every work's nodes: its items' series of their sites, its group
        val nodesOf = HashMap<Long, MutableSet<String>>()
        siteOf.forEach { (item, key) ->
            workOf[item]?.let { nodesOf.getOrPut(it) { HashSet() } += SiteSeriesRef(item.source, key).toString() }
        }
        groups.forEach { (group, works) ->
            works.forEach { nodesOf.getOrPut(it) { HashSet() } += "${TypeSeries.SAID}$group" }
        }
        val joined = Joined().apply { nodesOf.values.forEach(::joinAll) }
        // The same of the series of the sites alone: what they make without the user's word
        val bySites = Joined()
        nodesOf.values.forEach { nodes ->
            nodes.filter { !it.startsWith(TypeSeries.SAID) }.takeIf { it.isNotEmpty() }?.let(bySites::joinAll)
        }
        val worksOf = HashMap<String, MutableSet<Long>>()
        nodesOf.forEach { (work, nodes) -> worksOf.getOrPut(joined.root(nodes.first())) { HashSet() } += work }
        val itemsOf = HashMap<String, MutableList<ItemKey>>()
        siteOf
            .filterKeys { it in workOf }
            .forEach { (item, key) ->
                itemsOf.getOrPut(SiteSeriesRef(item.source, key).toString()) { ArrayList() } += item
            }
        return joined.nodes.groupBy(joined::root).map { (root, nodes) ->
            val sites = nodes.filter { !it.startsWith(TypeSeries.SAID) }.sorted()
            val said =
                nodes
                    .filter { it.startsWith(TypeSeries.SAID) }
                    .map { it.removePrefix(TypeSeries.SAID).toLong() }
                    .toSet()
            val works = worksOf[root].orEmpty()
            NormalizedSeries(
                sites.firstOrNull() ?: nodes.min(),
                sites.associate {
                    SiteSeriesRef(it.substringBefore('/'), it.substringAfter('/')) to itemsOf[it].orEmpty()
                },
                said,
                works,
                byHand = said.isNotEmpty() && !heldBySites(works, nodesOf, bySites),
            )
        }
    }

    /**
     * Whether the series of the sites alone hold the [works] in one series: each of them in a series of a site, and
     * those series one by the works they share ([bySites]) — the user's word on them then said nothing they do not.
     */
    private fun heldBySites(works: Set<Long>, nodesOf: Map<Long, Set<String>>, bySites: Joined): Boolean {
        val roots = works.map { work ->
            nodesOf[work].orEmpty().firstOrNull { !it.startsWith(TypeSeries.SAID) }?.let(bySites::root) ?: return false
        }
        return roots.toSet().size == 1
    }
}
