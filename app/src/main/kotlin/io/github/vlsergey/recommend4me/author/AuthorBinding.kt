package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(AuthorBinding::class.java)

/** The source's facet of the authors; null when it has none. */
internal fun authorFacetOf(s: SourceStore): String? = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

/**
 * Every author of every site bound to a normalized author ([AuthorRepository]), at first one of their own — never by
 * the likeness of a name: as the works naming them come, and at the start whatever was not bound yet.
 */
@Component
class AuthorBinding(private val stores: Stores) {

    /** The [keys] of the source not bound yet bound; one at a time: two at once would bind one author twice. */
    @Synchronized
    private fun bind(s: SourceStore, keys: Collection<String>) {
        val fresh = keys - s.authors.bindingsOf(s.id).keys
        if (fresh.isEmpty()) return
        s.authors.bindNew(s.id, fresh, Instant.now())
        log.info("{}: {} authors bound to normalized authors of their own", s.id, fresh.size)
    }

    /** The authors of the works [event] names bound at once, before anything reads the works. */
    @Order(AFTER_WORKS)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val s = stores.source(event.source) ?: return
        val facet = authorFacetOf(s) ?: return
        val keys = s.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() }.toSet()
        if (keys.isNotEmpty()) bind(s, keys)
    }

    /** Before anything reads the authors: every author of every site not bound yet bound. */
    @Order(BEFORE_READERS)
    @EventListener(ApplicationReadyEvent::class)
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        stores.sources.forEach { s ->
            try {
                authorFacetOf(s)?.let { bind(s, s.items.valueUses(it).keys) }
            } catch (e: Exception) {
                // A boundary of the start: the authors of one source not bound stop neither the other sources nor
                // the start of the application
                log.error("{}: the authors were not bound", s.id, e)
            }
        }
    }

    private companion object {
        /** Among the listeners of changed items: after the works are bound (-200), before the rest reads them. */
        const val AFTER_WORKS = -100

        /** Among the steps of the start: before every step that reads the authors. */
        const val BEFORE_READERS = -10
    }
}
