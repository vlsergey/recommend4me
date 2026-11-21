package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * WHAT TWO WORKS OF A PAIR HAVE WHOLLY ALIKE, as the user may choose the pairs by: the authors — the same normalized
 * authors, every one, none counted by a word "partly one" — and the title — the same, as the pair shows it (the user's
 * over the site's), apart from the case and the spaces around. Of a work of no normalized author, no authors alike. Of
 * the works as the search read them ([sides]: of the index of the list), nothing read again; of a work the search did
 * not read — one of no title — nothing alike.
 */
internal class WholeMatch(sides: List<SearchSide>) {
    private val bySource = sides.associateBy { it.store.id }

    /** Whether the two works of the [pair] have the same normalized authors, one at least. */
    fun authors(pair: Pair<ItemKey, ItemKey>): Boolean {
        val first = authorsOf(pair.first) ?: return false
        return first.isNotEmpty() && first == authorsOf(pair.second)
    }

    /** Whether the two works of the [pair] have the same title. */
    fun title(pair: Pair<ItemKey, ItemKey>): Boolean {
        val first = titleOf(pair.first) ?: return false
        return first == titleOf(pair.second)
    }

    private fun authorsOf(key: ItemKey): Set<Long>? = at(key)?.let { (side, i) -> side.authors[i].toHashSet() }

    private fun titleOf(key: ItemKey): String? = at(key)?.let { (side, i) -> side.titleTexts[i].trim().lowercase() }

    /** The side of the work [key] and its place in it; none of a work not read. */
    private fun at(key: ItemKey): Pair<SearchSide, Int>? {
        val side = bySource[key.source] ?: return null
        return side.ids.binarySearch(key.id).takeIf { it >= 0 }?.let { side to it }
    }
}
