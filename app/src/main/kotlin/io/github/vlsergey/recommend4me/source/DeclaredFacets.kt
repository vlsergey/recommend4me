package io.github.vlsergey.recommend4me.source

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(DeclaredFacets::class.java)

/**
 * A SOURCE'S FACETS ARE THOSE IT DECLARES, with the application's layer over them: on start the
 * values of a facet no longer declared — two merged into one, renamed — are removed and told of.
 * The source sets them anew from its pages under their new names when it reads them again.
 */
@Component
class DeclaredFacets(private val stores: Stores) {

    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            s.items.keepOnlyFacets(s.schema.facets.map { it.key }).forEach { (facet, n) ->
                log.info("{}: the facet {} is no longer declared: its {} values removed", s.id, facet, n)
            }
        }
    }
}
