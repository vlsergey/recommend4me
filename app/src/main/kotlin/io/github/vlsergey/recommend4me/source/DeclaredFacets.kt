package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.settings.Settings
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(DeclaredFacets::class.java)

/**
 * A SOURCE'S FACETS ARE THOSE IT DECLARES, with the application's layer over them: on start the values of a facet no
 * longer declared — two merged into one, renamed — are removed and told of. The source sets them anew from its pages
 * under their new names when it reads them again. Only when its facets are others than the last start kept
 * ([DECLARED]): a pass over every value of every work, never "just in case" (user, 2026-11-18; 2026-11-19 it ran out of
 * time on ficbook's 10M values and took the start down with it).
 */
@Component
class DeclaredFacets(private val stores: Stores, private val settings: Settings) {

    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            val declared = s.schema.facets.map { it.key }
            val name = DECLARED + s.id
            val told = declared.sorted().joinToString(",")
            if (settings.get(name) == told) return@forEach
            s.items.keepOnlyFacets(declared).forEach { (facet, n) ->
                log.info("{}: the facet {} is no longer declared: its {} values removed", s.id, facet, n)
            }
            settings.set(name, told)
        }
    }

    private companion object {
        /** The setting of a source's facets the last start kept, by the source. */
        const val DECLARED = "source.facets.declared."
    }
}
