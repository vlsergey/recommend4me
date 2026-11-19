package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import java.time.Instant
import org.slf4j.LoggerFactory
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
    private val tasks: Tasks,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(VECTORS.what) { _, reason -> schedule(START_DELAY_SECONDS, reason) }
    }

    /** Works came or changed: their sites' fandoms bound ([FandomNormalization]), maybe new ones. */
    @EventListener(WorkChangeEvent::class) fun itemsChanged() = schedule(QUIET_SECONDS, "items")

    /**
     * The vectors made after the quiet, in the queue of the works; the [reason], a key the interface words, told there.
     * Those made told out of the queue; a failure told in the log, and the next change plans it again.
     */
    private fun schedule(delaySeconds: Long, reason: String) {
        if (sites().isEmpty()) return
        tasks.plan(
            VECTORS,
            Instant.now().plusSeconds(delaySeconds),
            reason,
            after = { made: Int -> if (made > 0) events.publishEvent(FandomVectorChangeEvent) },
        ) {
            fill()
        }
    }

    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

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

    private companion object {
        /** After the start: the sources read, the encoder loaded. */
        const val START_DELAY_SECONDS = 5 * 60L

        /** The quiet after a change: a page is sent several times as it grows. */
        const val QUIET_SECONDS = 10L

        /** The making of the vectors, as the queue of the works tells it. */
        val VECTORS = Task(TaskKind.FANDOM_VECTORS)
    }
}
