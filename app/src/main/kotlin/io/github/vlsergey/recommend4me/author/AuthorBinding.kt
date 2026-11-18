package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(AuthorBinding::class.java)

/** The source's facet of the authors; null when it has none. */
internal fun authorFacetOf(s: SourceStore): String? = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

/**
 * Every author of every site bound to a normalized author ([AuthorRepository]), at first one of their own — never by
 * the likeness of a name: as the works naming them come, as another site's author lists theirs as a place of theirs
 * ([AuthorPlaces]) — of no work known yet — and at the start whatever was not bound yet. The one name the binding goes
 * by is the one a source says is a key ([NamedAuthors]).
 */
@Component
class AuthorBinding(private val stores: Stores, private val named: NamedAuthors) {

    /**
     * The [keys] of the source not bound yet bound — a key named twice once; one at a time: two at once would bind one
     * author twice.
     */
    @Synchronized
    fun bind(s: SourceStore, keys: Collection<String>) {
        // Of the keys alone, not every author of the site: of ficbook's 357 thousand at every page read
        val fresh = keys.toSet() - s.authors.bindingsOf(s.id, keys).keys
        if (fresh.isEmpty()) return
        s.authors.bindNew(s.id, fresh, Instant.now())
        log.info("{}: {} authors bound to normalized authors of their own", s.id, fresh.size)
    }

    /**
     * The authors of the works [event] names bound at once, before anything reads the works; those the site gave keys
     * of now one with the same names it gave alone ([NamedAuthors]).
     */
    @Order(AFTER_WORKS)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val s = stores.source(event.source) ?: return
        val facet = authorFacetOf(s) ?: return
        val keys = s.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() }.toSet()
        if (keys.isNotEmpty()) {
            bind(s, keys)
            named.merge(s, keys)
        }
    }

    companion object {
        /** Among the listeners of changed items: after the works are bound (-200), before the rest reads them. */
        private const val AFTER_WORKS = -100

        /** Among the steps of the start: before every step that reads the authors. */
        const val BEFORE_READERS = -10
    }
}
