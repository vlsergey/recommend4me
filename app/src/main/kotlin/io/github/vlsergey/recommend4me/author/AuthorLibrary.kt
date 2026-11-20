package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * The works and the authors of a type, read for one request: of every work its authors on its site by their keys, each
 * with the normalized author bound to it; its normalized authors; of every author their works and the sites of them.
 */
internal class AuthorLibrary(val writersOf: Map<ItemKey, Map<String, Long>>) {
    val authorsOf: Map<ItemKey, Set<Long>> = writersOf.mapValues { it.value.values.toSet() }
    val worksOf: Map<Long, List<ItemKey>> =
        HashMap<Long, MutableList<ItemKey>>().also { out ->
            authorsOf.forEach { (work, authors) -> authors.forEach { out.getOrPut(it) { ArrayList() } += work } }
        }

    /** Of every author the sites they have works on. */
    val sitesOf: Map<Long, Set<String>> by lazy {
        HashMap<Long, MutableSet<String>>().also { out ->
            authorsOf.forEach { (work, authors) -> authors.forEach { out.getOrPut(it) { HashSet() } += work.source } }
        }
    }

    companion object {
        /**
         * The library of the works of the authors [ids] alone: their accounts on the sites, the works of those, and
         * every author of those works — by the keys, a request's (owner, 2026-11-20: no request reads every work's
         * authors).
         */
        fun ofAuthors(type: TypeStore, ids: Collection<Long>): AuthorLibrary {
            val writersOf = HashMap<ItemKey, Map<String, Long>>()
            type.authors
                .sitesOf(ids)
                .values
                .flatten()
                .groupBy { it.source }
                .forEach { (source, accounts) ->
                    val s = type.source(source) ?: return@forEach
                    val facet = authorFacetOf(s) ?: return@forEach
                    val items = s.items.itemsWith(facet, accounts.map { it.author }).values.flatten().distinct()
                    val keys = s.items.facetsOf(items).mapValues { it.value[facet].orEmpty() }
                    val bound = s.authors.bindingsOf(s.id, keys.values.flatten().distinct())
                    keys.forEach { (id, own) ->
                        writersOf[ItemKey(source, id)] = own.mapNotNull { k -> bound[k]?.let { k to it } }.toMap()
                    }
                }
            return AuthorLibrary(writersOf)
        }

        /** The library of the type, every source's works read in one pass: a search's, never a request's. */
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
