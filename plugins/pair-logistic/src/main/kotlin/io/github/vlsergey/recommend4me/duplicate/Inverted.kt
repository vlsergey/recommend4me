package io.github.vlsergey.recommend4me.duplicate

/**
 * THE WORKS OF A SITE BY WHAT THEY HAVE: of every value of the type (by its place) the works of the [site] that have it
 * and its weight in each, one list after another ([at] where a value's begin, [items], [weights]); of every author the
 * works of it ([authors]: the author to its place among [authorAt], whose works begin there in [authorItems]). How a
 * work of another site meets every work of this one at once: through its own values and authors, not every pair.
 */
internal class Inverted(site: SiteWorks, valueCount: Int) {
    val at = IntArray(valueCount + 1)
    val items: IntArray
    val weights: FloatArray

    /** Every author of a work of the site, by its place. */
    val authors: Map<Long, Int>
    val authorAt: IntArray
    val authorItems: IntArray

    init {
        site.values.forEach { own -> own.forEach { at[it + 1]++ } }
        for (v in 0 until valueCount) at[v + 1] += at[v]
        items = IntArray(at[valueCount])
        weights = FloatArray(at[valueCount])
        val filled = at.copyOf(valueCount)
        site.values.forEachIndexed { i, own ->
            own.forEachIndexed { k, v ->
                items[filled[v]] = i
                weights[filled[v]] = site.weights[i][k]
                filled[v]++
            }
        }
        val byAuthor = HashMap<Long, MutableList<Int>>()
        site.authors.forEachIndexed { i, own -> own.distinct().forEach { byAuthor.getOrPut(it) { ArrayList() } += i } }
        val order = byAuthor.keys.sorted()
        authors = order.withIndex().associate { it.value to it.index }
        authorAt = IntArray(order.size + 1)
        order.forEachIndexed { k, a -> authorAt[k + 1] = authorAt[k] + byAuthor.getValue(a).size }
        authorItems = IntArray(authorAt[order.size])
        order.forEachIndexed { k, a ->
            byAuthor.getValue(a).forEachIndexed { q, i -> authorItems[authorAt[k] + q] = i }
        }
    }

    /** The works of the author [a], by their places; none of an author of no work of the site. */
    fun ofAuthor(a: Long): IntRange = authors[a]?.let { k -> authorAt[k] until authorAt[k + 1] } ?: IntRange.EMPTY
}
