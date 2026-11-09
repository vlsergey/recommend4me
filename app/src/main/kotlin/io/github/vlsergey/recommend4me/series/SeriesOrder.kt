package io.github.vlsergey.recommend4me.series

import java.util.PriorityQueue

/**
 * What a series of a site says of the order of a normalized series' works: their numbers there ([numbered], work to
 * number), and how much it has — its works and their volume — which tells how far its word goes against another site's.
 */
class SiteOrder(val numbered: Map<Long, Int>, val works: Int, val volume: Double)

/**
 * THE ORDER OF THE WORKS OF A NORMALIZED SERIES, by pairs: every series of a site that numbers two works says the one
 * before the other, and the sites are heard one after another — the one with the most works first, of as many the one
 * of the greater volume — each pair a site says kept unless it goes against the pairs kept of the sites before it. The
 * works go in an order every pair kept holds; of two no pair orders, the one standing nearer the start of its sites'
 * lists first, then as [rest] orders them. Works no site numbers go after, as [rest] orders them.
 */
object SeriesOrder {

    /** The [works] in order by the [sites]' numbers. */
    fun of(works: Collection<Long>, sites: Collection<SiteOrder>, rest: Comparator<Long>): List<Long> {
        val own = works.toSet()
        val heard = sites.sortedWith(compareByDescending<SiteOrder> { it.works }.thenByDescending { it.volume })
        val after = HashMap<Long, MutableSet<Long>>()
        val places = HashMap<Long, MutableList<Double>>()
        heard.forEach { site ->
            val placed = site.numbered.entries.filter { it.key in own }.sortedBy { it.value }.map { it.key }
            placed.forEachIndexed { i, a ->
                places.getOrPut(a) { ArrayList() } += (i + 1).toDouble() / placed.size
                placed.drop(i + 1).forEach { b -> if (!reaches(after, b, a)) after.getOrPut(a) { HashSet() } += b }
            }
        }
        val numbered = places.keys
        val near = compareBy<Long> { places.getValue(it).average() }.then(rest)
        return sorted(numbered, after, near) + own.filter { it !in numbered }.sortedWith(rest)
    }

    /** Whether [from] is before [to] by the pairs kept [after]: a pair "[to] before [from]" would go against it. */
    private fun reaches(after: Map<Long, Set<Long>>, from: Long, to: Long): Boolean {
        val seen = HashSet<Long>()
        val left = ArrayDeque(listOf(from))
        var found = false
        while (left.isNotEmpty() && !found) {
            val w = left.removeFirst()
            found = w == to
            if (seen.add(w)) after[w]?.let { left.addAll(it) }
        }
        return found
    }

    /** The [works] in an order every pair [after] holds, of the works free to go next the first by [near]. */
    private fun sorted(works: Set<Long>, after: Map<Long, Set<Long>>, near: Comparator<Long>): List<Long> {
        val before = HashMap<Long, Int>()
        after.values.forEach { next -> next.forEach { before.merge(it, 1, Int::plus) } }
        val free = PriorityQueue(near).apply { addAll(works.filter { (before[it] ?: 0) == 0 }) }
        val out = ArrayList<Long>(works.size)
        while (free.isNotEmpty()) {
            val w = free.poll()
            out += w
            after[w]?.forEach { next -> if (before.merge(next, -1, Int::plus) == 0) free += next }
        }
        return out
    }
}
