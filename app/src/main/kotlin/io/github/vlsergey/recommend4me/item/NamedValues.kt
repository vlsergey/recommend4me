package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(NamedValues::class.java)

/**
 * A VALUE A SITE GAVE BY ITS NAME ALONE IS THE VALUE OF THAT NAME THE SITE GIVES A KEY OF, ON EVERY WORK (owner,
 * 2026-11-20: "let us carry it over"): a dump keyed what it gave by the name — "name:Сэм и Кэт", under the prefix the
 * source says of its facet ([io.github.vlsergey.recommend4me.source.FacetDef.byName]) — and as soon as a page of the
 * site gives a value of that name its key, every work the name was given to has the key in its place: what a page read
 * once tells of every work of the name, not of its own alone. Never a name two keys of the facet have — two fandoms of
 * one name — nor a name the site gave another since: a value renamed stays by its old name till its own work is read.
 * The authors so keyed are one person as well ([io.github.vlsergey.recommend4me.author.NamedAuthors]).
 */
@Component
class NamedValues(private val stores: Stores) {

    /** The values the items [event] names were given keyed in place of their names on every other item. */
    @EventListener
    fun itemsWritten(event: SiteItemWriteEvent) {
        val s = stores.source(event.source) ?: return
        val facets = s.schema.facets.filter { it.byName != null }
        if (facets.isEmpty()) return
        val values = s.items.facetsOf(event.ids).values
        facets.forEach { f -> keyed(s, f.key, f.byName!!, values.flatMap { it[f.key].orEmpty() }.toSet()) }
    }

    /**
     * The keys given by the names of the site's own [keys] of the facet made those keys: of a name one key of the facet
     * has, among them and in the dictionary.
     */
    @Synchronized
    private fun keyed(s: SourceStore, facet: String, prefix: String, keys: Set<String>) {
        val names = s.items.facetNames(facet, keys.filterNot { it.startsWith(prefix) }).mapValues { it.value.trim() }
        val owners =
            names.entries
                .groupBy({ it.value }, { it.key })
                .filter { (name, of) -> name.isNotEmpty() && of.size == 1 }
                .mapValues { it.value.single() }
        // Of the names some work still has, by the index of the values: of most none
        val given = s.items.given(facet, owners.keys.map { prefix + it }).map { it.removePrefix(prefix) }
        if (given.isEmpty()) return
        val known = s.items.keysNamed(facet, given)
        var moved = 0
        given.forEach { name ->
            val key = owners.getValue(name)
            if (known[name].orEmpty().filterNot { it.startsWith(prefix) } != listOf(key)) return@forEach
            moved += s.items.rekey(facet, prefix + name, key).size
        }
        if (moved > 0) log.info("{}: {} values of {} given by their names keyed so", s.id, moved, facet)
    }
}
