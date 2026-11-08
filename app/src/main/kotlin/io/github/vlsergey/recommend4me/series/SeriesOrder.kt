package io.github.vlsergey.recommend4me.series

/**
 * THE ORDER OF THE WORKS OF A NORMALIZED SERIES, by pairs: every series of a site that numbers two of its works says
 * the one before the other, and a work goes before another by how many works it is said to be before less how many it
 * is said to be after, over every site — a site's order wherever the sites agree, the most of them where they do not.
 * Of an equal count, the one standing nearer the start of its sites' lists first; works no site numbers go after, as
 * [rest] orders them.
 */
object SeriesOrder {

    /** The [works] in order by the sites' numbers [numbered] — of each site, work to its number. */
    fun of(works: Collection<Long>, numbered: Collection<Map<Long, Int>>, rest: Comparator<Long>): List<Long> {
        val score = HashMap<Long, Int>()
        val places = HashMap<Long, MutableList<Double>>()
        numbered.forEach { order ->
            val placed = order.entries.filter { it.key in works }.sortedBy { it.value }
            placed.forEachIndexed { i, a ->
                placed.drop(i + 1).forEach { b ->
                    if (a.value < b.value) {
                        score.merge(a.key, 1, Int::plus)
                        score.merge(b.key, -1, Int::plus)
                    }
                }
                score.putIfAbsent(a.key, 0)
                places.getOrPut(a.key) { ArrayList() } += (i + 1).toDouble() / placed.size
            }
        }
        val ranked =
            works
                .filter { it in score }
                .sortedWith(compareByDescending<Long> { score[it] }.thenBy { places.getValue(it).average() }.then(rest))
        return ranked + works.filter { it !in score }.sortedWith(rest)
    }
}
