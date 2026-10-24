package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(Answering::class.java)

/**
 * THE USER'S ANSWER ON A VALUE OF A WORK'S FACET, written as the marking of a work writes it — from
 * the work's own marking, from the screen of the pairings alike — and told: what learns of it learns
 * anew in the background.
 */
@Component
class Answering(private val events: ApplicationEventPublisher) {

    /**
     * The answer [added] on the value [key] of the [facet] written; [name] kept of a value the site
     * does not name. Of a facet of one value a value added takes the place of the user's others; a
     * character said one of the work's main ones is its secondary one no more, and the other way round.
     */
    fun write(store: SourceStore, item: String, facet: String, key: String, added: Boolean, name: String? = null) {
        store.corrections.setFacet(item, facet, key, added, name, Instant.now())
        if (added && store.schema.facet(facet)?.single == true) {
            store.corrections.facetsOf(item).filter { it.facet == facet && it.added && it.key != key }.forEach { store.corrections.unsetFacet(item, facet, it.key) }
        }
        UniverseFacet.otherCharacters(facet)?.takeIf { added && key !in UniverseFacet.ORIGINAL_CHARACTERS }?.let { other ->
            if (store.corrections.facetsOf(item).any { it.facet == other && it.key == key && it.added }) store.corrections.unsetFacet(item, other, key)
        }
    }

    /** A tag the work has by a tag under it the user confirmed is not said to be not the work's: the answer on that one is. */
    fun refuseImplied(store: SourceStore, item: String, facet: String, key: String) {
        val hierarchy = store.hierarchies()[facet] ?: return
        val under = hierarchy.descendants(key)
        store.corrections.facetsOf(item).firstOrNull { it.facet == facet && it.added && it.key in under }?.let { held ->
            throw Refusal.TAG_IMPLIED.refuse(
                HttpStatus.CONFLICT, "$key is above ${held.key}, which the user confirmed of $item",
                "tag" to (store.tags.shown(listOf(held.key))[held.key] ?: held.key),
            )
        }
    }

    /**
     * An answer on the work's [facets]: [change] written, and nothing else — nothing of the work read
     * back. The models of the facets, the search and the model of the grades read the user's answers:
     * fitted, indexed and learnt anew in the background ([FacetAnswered]).
     */
    fun answered(store: SourceStore, item: String, facets: Collection<String>, change: () -> Unit) {
        val started = System.currentTimeMillis()
        change()
        facets.forEach { events.publishEvent(FacetAnswered(store.type, store.id, item, it)) }
        log.info("{}: an answer on {} of {} written in {} ms", store.id, facets.joinToString(), item, System.currentTimeMillis() - started)
    }
}
