package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(UnusedAuthors::class.java)

/**
 * AN AUTHOR OF A SITE NO WORK OF ITS SITE HAS ANY MORE, WHOSE NAME ANOTHER KEY OF THE SITE HAS — the key a dump of the
 * site gave by the name alone, the site's own page having given the works the author's own key since (user,
 * 2026-11-17): the two are one person, the name a site's account — its normalized author merged into the other's
 * ([MergedBy.Name]). Only a normalized author of that key alone, not told apart from the other: one the user merged or
 * took apart stays as the user left it. A key of no works whose name no other key with works has stays.
 */
@Component
class UnusedAuthors(private val stores: Stores) {

    /** At the start, every site's author bound first ([AuthorBinding.start]), before anything reads them. */
    @Order(AuthorBinding.BEFORE_READERS + 1)
    @EventListener(ApplicationReadyEvent::class)
    // The start of the application: whatever fails is told in the log, and the authors stay as they were
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        stores.sources.forEach { s ->
            try {
                val merged = merge(s)
                if (merged > 0) log.info("{}: {} authors of no works merged with the key of their name", s.id, merged)
            } catch (e: Exception) {
                log.error("{}: the unused authors were not merged", s.id, e)
            }
        }
    }

    /** The source's unused authors of a name another key with works has merged into its author; how many. */
    fun merge(s: SourceStore): Int {
        val facet = authorFacetOf(s) ?: return 0
        val used = s.items.valueUses(facet).keys
        val names = s.items.facetNames(facet)
        val bound = s.authors.bindingsOf(s.id)
        val withWorks = names.filterKeys { it in used }.entries.groupBy({ it.value }, { it.key })
        val apart = s.authors.distinct.told().keys
        val alone = s.authors.sitesOf(bound.values).filterValues { it.size == 1 }.keys
        var merged = 0
        bound.forEach { (key, from) ->
            if (key in used || from !in alone) return@forEach
            val twin = names[key]?.let { withWorks[it] }?.singleOrNull() ?: return@forEach
            val into = bound[twin] ?: return@forEach
            if (into == from || AuthorRepository.pair(from, into) in apart) return@forEach
            s.authors.merge(from, into, Instant.now(), MergedBy.Name)
            merged++
        }
        return merged
    }
}
