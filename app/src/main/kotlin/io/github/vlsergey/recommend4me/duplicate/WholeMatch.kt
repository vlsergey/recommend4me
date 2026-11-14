package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * WHAT TWO WORKS OF A PAIR HAVE WHOLLY ALIKE, as the user may choose the pairs by: the authors — the same normalized
 * authors, every one, none counted by a word "partly one" — and the title — the same, as the pair shows it (the user's
 * over the site's), apart from the case and the spaces around. Of a work of no normalized author, no authors alike.
 * Read of every work of the sources the [keys] are of, one pass per source, for the request.
 */
internal class WholeMatch(type: TypeStore, keys: Collection<ItemKey>, authors: Boolean, titles: Boolean) {
    private val authorsOf = HashMap<ItemKey, Set<Long>>()
    private val titleOf = HashMap<ItemKey, String>()

    init {
        keys
            .map { it.source }
            .distinct()
            .forEach { source ->
                val s = type.source(source) ?: return@forEach
                if (authors) {
                    val facet = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key
                    val bound = s.authors.bindingsOf(s.id)
                    facet?.let { f ->
                        s.items.forEachFacet(f) { id, own ->
                            authorsOf[ItemKey(source, id)] = own.mapNotNullTo(HashSet()) { bound[it] }
                        }
                    }
                }
                if (titles) {
                    val written = s.corrections.allFields()
                    s.items.titles().forEach { (id, site) ->
                        titleOf[ItemKey(source, id)] = Corrected.title(site, written[id].orEmpty()).trim().lowercase()
                    }
                }
            }
    }

    /** Whether the two works of the [pair] have the same normalized authors, one at least. */
    fun authors(pair: Pair<ItemKey, ItemKey>): Boolean {
        val first = authorsOf[pair.first].orEmpty()
        return first.isNotEmpty() && first == authorsOf[pair.second]
    }

    /** Whether the two works of the [pair] have the same title. */
    fun title(pair: Pair<ItemKey, ItemKey>): Boolean {
        val first = titleOf[pair.first] ?: return false
        return first == titleOf[pair.second]
    }
}
