package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(NamedAuthors::class.java)

/**
 * AN AUTHOR A SITE GAVE BY THE NAME ALONE IS THE AUTHOR OF THAT NAME THE SITE GIVES A KEY OF ITS OWN (user, 2026-11-18:
 * "let us merge"): the keys a dump gave by the names, under the prefix the source says of them ([FacetDef.byName]
 * [io.github.vlsergey.recommend4me.source.FacetDef.byName]) — "name:Lorem" — and the site's own key of a value named
 * "Lorem" are one person, the name an account of the site. As soon as a page gives the key, the normalized author of
 * the name is merged into its ([MergedBy.Name]), every work the dump gave the name of — not yet read on the site — one
 * author's with those read. Never two the user told apart; never of a name two keys of the site have.
 */
@Component
class NamedAuthors {

    /**
     * The authors of the [source] given by their names merged into those of the same names the site gave keys of, of
     * the site's own [keys] — of the works changed; how many merged.
     */
    @Synchronized
    fun merge(source: SourceStore, keys: Collection<String>): Int {
        val (facet, prefix) =
            source.schema.facets.firstNotNullOfOrNull { f ->
                f.byName?.takeIf { f.role == FacetRole.AUTHOR }?.let { f.key to it }
            } ?: return 0
        val names = source.items.facetNames(facet, keys.filterNot { it.startsWith(prefix) })
        // The key given by a name to the site's own key of that name, of a name one key has
        val owners =
            names.entries
                .filterNot { it.key.startsWith(prefix) }
                .groupBy({ prefix + it.value.trim() }, { it.key })
                .mapNotNull { (named, own) -> own.singleOrNull()?.let { named to it } }
        return if (owners.isEmpty()) 0 else merged(source, owners)
    }

    /** The normalized author of every key given by a name merged into that of its own key; how many merged. */
    private fun merged(source: SourceStore, owners: List<Pair<String, String>>): Int {
        val bound = source.authors.bindingsOf(source.id, owners.flatMap { listOf(it.first, it.second) })
        val apart = source.authors.distinct.told().keys
        val now = Instant.now()
        // A normalized author merged into another: those after it of the one it is now
        val gone = HashMap<Long, Long>()
        fun current(id: Long): Long = generateSequence(id) { gone[it] }.last()
        var merged = 0
        owners.forEach { (named, own) ->
            val from = bound[named]?.let(::current) ?: return@forEach
            val into = bound[own]?.let(::current) ?: return@forEach
            if (from == into || AuthorRepository.pair(from, into) in apart) return@forEach
            source.authors.merge(from, into, now, MergedBy.Name)
            gone[from] = into
            merged++
        }
        if (merged > 0) log.info("{}: {} authors given by their names merged with their own keys", source.id, merged)
        return merged
    }
}
