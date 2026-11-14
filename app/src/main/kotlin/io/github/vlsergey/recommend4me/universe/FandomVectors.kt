package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FandomVectors::class.java)

/**
 * THE VECTORS OF THE NAMES OF THE SITES' FANDOMS AND OF THEIR DESCRIPTIONS, MADE IN THE BACKGROUND: every one not
 * encoded yet by the text encoder in use, kept by its source as any short text ([PhraseVectors]) — what the universes
 * alike one are told by ([UniverseViews.similar]), which reads only what is kept. Made after the works change, once
 * they have paused, and a while after the start.
 */
@Component
class FandomVectors(
    private val stores: Stores,
    private val phrases: PhraseVectors,
    private val workload: Workload,
    private val events: ApplicationEventPublisher,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fandom-vectors").apply { isDaemon = true }
    }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    @EventListener(ApplicationReadyEvent::class) fun start() = schedule(START_DELAY_SECONDS, "start")

    /** Works came or changed: their sites' fandoms bound ([FandomNormalization]), maybe new ones. */
    @EventListener(ItemsChanged::class) fun itemsChanged() = schedule(QUIET_SECONDS, "items")

    /** The vectors made after the quiet; the [reason], a key the interface words, told in the queue of the works. */
    private fun schedule(delaySeconds: Long, reason: String) {
        if (sites().isEmpty()) return
        workload.plan(VECTORS, Instant.now().plusSeconds(delaySeconds), reason)
        val next = scheduler.schedule({ fillInBackground() }, delaySeconds, TimeUnit.SECONDS)
        pending.getAndSet(next)?.cancel(false)
    }

    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

    /** The vectors made in the queue of the works: a task of the background, which logs what fails. */
    // The background's loop: whatever fails is told in the log, and the next change plans it again
    @Suppress("TooGenericExceptionCaught")
    private fun fillInBackground() {
        try {
            if (workload.run(VECTORS) { fill() } > 0) events.publishEvent(FandomVectorsMade)
        } catch (e: Exception) {
            log.warn("The vectors of the fandoms' names were not made: {}", e.message, e)
        }
    }

    /**
     * Every name of a site's fandom and every description its site gives of one — a synonym of the name
     * ([SiteFandomVectors]) — not encoded yet encoded and kept; how many.
     */
    fun fill(): Int =
        sites().sumOf { (s, facet) ->
            val names = s.items.facetNames(facet)
            val said = s.items.facetDescriptions(facet, names.keys).values
            val missing =
                phrases.missing(s, (names.values + said).map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            if (missing.isNotEmpty()) {
                phrases.of(s, missing)
                log.info("{}: the names and descriptions of fandoms encoded: {}", s.id, missing.size)
            }
            missing.size
        }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private companion object {
        /** After the start: the sources read, the encoder loaded. */
        const val START_DELAY_SECONDS = 5 * 60L

        /** The quiet after a change: a page is sent several times as it grows. */
        const val QUIET_SECONDS = 10L

        /** The making of the vectors, as the queue of the works tells it. */
        val VECTORS = Work("fandomVectors")
    }
}
