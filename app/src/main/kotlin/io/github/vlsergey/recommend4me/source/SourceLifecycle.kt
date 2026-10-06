package io.github.vlsergey.recommend4me.source

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SourceLifecycle::class.java)

/** Starts the background work of every source once the application is up, and stops it at the end. */
@Component
class SourceLifecycle(
    private val stores: Stores,
    private val contexts: SourceContexts,
    /** Off in tests, where no source is to reach its site. */
    @Value("\${recommend4me.sources.background:true}") private val enabled: Boolean,
) {
    @Order(20)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        stores.sources.forEach { s ->
            try {
                s.source.start(contexts.of(s.id))
            } catch (e: Exception) {
                log.error("{}: the background work did not start", s.id, e)
            }
        }
    }

    @PreDestroy
    fun stop() {
        stores.sources.forEach { s -> runCatching { s.source.stop() } }
    }
}
