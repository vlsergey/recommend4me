package io.github.vlsergey.recommend4me.signal

import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(DeclaredSignals::class.java)

/**
 * A SOURCE'S SIGNALS ARE THOSE IT DECLARES: on start the values of a signal the source no longer
 * declares — renamed, made a standard one — are removed and told of. The source sets them anew
 * from its pages under their new names when it reads them again.
 */
@Component
class DeclaredSignals(private val stores: Stores) {

    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            s.signals.keepOnly(s.source.signals.map { it.key }).forEach { (signal, n) ->
                log.info("{}: the signal {} is no longer declared: its {} values removed", s.id, signal, n)
            }
        }
    }
}
