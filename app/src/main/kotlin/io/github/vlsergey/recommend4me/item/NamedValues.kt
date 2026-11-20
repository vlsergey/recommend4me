package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(NamedValues::class.java)

/**
 * AN AUTHOR A SITE GAVE BY THE NAME ALONE IS THE ACCOUNT OF THAT NAME THE SITE GIVES A KEY OF, ON EVERY WORK (owner,
 * 2026-11-20: "let us carry it over"; "only the authors can be taken for unique"): a dump keyed the authors by their
 * names — "name:Мышилла", under the prefix the source says of its facet of the authors
 * ([io.github.vlsergey.recommend4me.source.FacetDef.byName]) — and as soon as a page of the site gives an author of
 * that name a key, every work the name was given to has the key in its place: an account's name is one account of the
 * site at a time. Of no other facet: two fandoms of a site may have one name (ficbook's «12 стульев» of two addresses,
 * 478 such names of 17 thousand), and a tag may too — a dump's name of either does not tell which of them a work is of,
 * and the site's dictionary known is never all of it. Never a name two keys have even so, nor a name the site gave
 * another since: an account renamed stays by its old name till its own work is read. The authors so keyed are one
 * person as well ([io.github.vlsergey.recommend4me.author.NamedAuthors]).
 *
 * The works of a name are keyed in the queue of the computations: each is a work written anew, read again by whatever
 * listens to the items written — a third of a second a work of ficbook, minutes of an author of hundreds of works,
 * which no page sent waits for (2026-11-20: 12 s a page of a fandom of 20 works, when fandoms were keyed so). The keys
 * whose names wait are kept with the work of the queue, of the source and the facet.
 */
@Component
class NamedValues(private val stores: Stores, private val tasks: Tasks) {

    // What the last run of the application left undone of it: planned again by the queue at its start, of the keys the
    // queue kept
    init {
        tasks.resumption.resumes(TaskKind.KEY_NAMED_VALUES) { left, reason ->
            val source = left.work.source
            val facet = left.work.facet
            if (source != null && facet != null) plan(source, facet, reason)
        }
    }

    /** The authors the items [event] names were given keyed in place of their names on every other item, later. */
    @EventListener
    fun itemsWritten(event: SiteItemWriteEvent) {
        val s = stores.source(event.source) ?: return
        val facets = s.schema.facets.filter { it.byName != null && it.role == FacetRole.AUTHOR }
        if (facets.isEmpty()) return
        val values = s.items.facetsOf(event.ids).values
        facets.forEach { f ->
            val owners = owners(s, f.key, f.byName!!, values.flatMap { it[f.key].orEmpty() }.toSet())
            if (owners.isNotEmpty()) {
                tasks.keys.want(work(s.id, f.key), owners.values)
                plan(s.id, f.key, "items")
            }
        }
    }

    /** The work of the queue of the source's facet. */
    private fun work(source: String, facet: String) = Task(TaskKind.KEY_NAMED_VALUES, source = source, facet = facet)

    /** The names of the keys waiting of the source's facet given those keys on every item, in the queue. */
    private fun plan(source: String, facet: String, reason: String) =
        tasks.plan(work(source, facet), Instant.now(), reason) {
            val work = work(source, facet)
            val taken = tasks.keys.wanted(work)
            val s = stores.source(source)
            val prefix = s?.schema?.facets?.firstOrNull { it.key == facet }?.byName
            if (s != null && prefix != null) keyed(s, facet, prefix, taken.toSet())
            tasks.keys.done(work, taken)
        }

    /**
     * Of the site's own [keys] of the facet, those whose names some item still has by the name alone, of a name one key
     * of the facet has, among them and in the dictionary: name to key.
     */
    private fun owners(s: SourceStore, facet: String, prefix: String, keys: Set<String>): Map<String, String> {
        val names = s.items.facetNames(facet, keys.filterNot { it.startsWith(prefix) }).mapValues { it.value.trim() }
        val owners =
            names.entries
                .groupBy({ it.value }, { it.key })
                .filter { (name, of) -> name.isNotEmpty() && of.size == 1 }
                .mapValues { it.value.single() }
        // Of the names some work still has, by the index of the values: of most none
        val given =
            if (owners.isEmpty()) emptyList()
            else s.items.given(facet, owners.keys.map { prefix + it }).map { it.removePrefix(prefix) }
        if (given.isEmpty()) return emptyMap()
        val known = s.items.keysNamed(facet, given)
        return given
            .filter { known[it].orEmpty().filterNot { k -> k.startsWith(prefix) } == listOf(owners.getValue(it)) }
            .associateWith { owners.getValue(it) }
    }

    /** The names of the [keys] of the facet given those keys on every item, as they are now. */
    private fun keyed(s: SourceStore, facet: String, prefix: String, keys: Set<String>) {
        val moved =
            owners(s, facet, prefix, keys).entries.sumOf { (name, key) ->
                s.items.rekey(facet, prefix + name, key).size.also {
                    // Its works the own key's, the account by the name is that account: gone, one account of the site
                    s.authors.named(s.id, prefix + name, key)
                }
            }
        if (moved > 0) log.info("{}: {} values of {} given by their names keyed so", s.id, moved, facet)
    }
}
