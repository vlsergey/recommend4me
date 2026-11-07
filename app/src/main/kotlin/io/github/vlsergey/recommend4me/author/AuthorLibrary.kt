package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * The works and the authors of a type, read for one request: of every work its authors on its site by their keys, each
 * with the normalized author bound to it; its normalized authors; of every author their works.
 */
internal class AuthorLibrary(val writersOf: Map<ItemKey, Map<String, Long>>) {
    val authorsOf: Map<ItemKey, Set<Long>> = writersOf.mapValues { it.value.values.toSet() }
    val worksOf: Map<Long, List<ItemKey>> =
        HashMap<Long, MutableList<ItemKey>>().also { out ->
            authorsOf.forEach { (work, authors) -> authors.forEach { out.getOrPut(it) { ArrayList() } += work } }
        }

    companion object {
        /** The library of the type, every source's works read in one pass. */
        fun of(type: TypeStore): AuthorLibrary {
            val writersOf = HashMap<ItemKey, Map<String, Long>>()
            type.sources.forEach { s ->
                val facet = authorFacetOf(s) ?: return@forEach
                val bound = s.authors.bindingsOf(s.id)
                s.items.forEachFacet(facet) { id, keys ->
                    writersOf[ItemKey(s.id, id)] = keys.mapNotNull { k -> bound[k]?.let { k to it } }.toMap()
                }
            }
            return AuthorLibrary(writersOf)
        }
    }
}
