package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(CastNames::class.java)

/** The texts of the casts of these works of a source were encoded: the models that read them read them anew. */
data class SiteItemCastEncodeEvent(val source: String, val ids: List<String>)

/**
 * THE TEXTS OF THE SAID CASTS ENCODED, IN THE BACKGROUND: of every work whose line changed, the texts every way of
 * reading the casts asks for ([CastReader.encoded]) — the names, the pairings written so — encoded and kept as the
 * short texts are ([PhraseVectors]), a text once, after a quiet; the works waiting kept in the queue. In the queue of
 * the computations ([Tasks]), never on the thread of a request: the models read the vectors kept alone ([CastReading]).
 * The queue of the works waiting is the only thing kept here.
 */
@Component
class CastNames(
    private val stores: Stores,
    private val plugins: Plugins,
    private val phrases: PhraseVectors,
    private val tasks: Tasks,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WHAT) { _, reason -> schedule(START_DELAY_SECONDS, reason) }
    }

    @EventListener
    fun itemsChanged(event: SiteItemWriteEvent) {
        val store = stores.source(event.source)?.takeIf(::encodes) ?: return
        tasks.keys.want(WORK, event.ids.map { "${store.id}$SEP$it" })
        schedule(QUIET_SECONDS, "items")
    }

    /** Whether a way of reading asks for texts of the source's casts at all: it has a line, and a way is installed. */
    private fun encodes(store: SourceStore): Boolean =
        WorkCasts.facetOf(store) != null && plugins.castReaders.isNotEmpty()

    /**
     * The works the queue keeps waiting ([TaskKeys.wanted]) — of every source, one work — encoded after [delaySeconds]
     * of quiet; whatever reads them again told out of the queue, it plans its own work.
     */
    private fun schedule(delaySeconds: Long, reason: String) =
        tasks.plan(
            WORK,
            Instant.now().plusSeconds(delaySeconds),
            reason,
            after = { encoded: Map<String, List<String>> ->
                encoded.forEach { (source, ids) ->
                    if (ids.isNotEmpty()) events.publishEvent(SiteItemCastEncodeEvent(source, ids))
                }
            },
        ) {
            val taken = tasks.keys.wanted(WORK)
            val encoded =
                taken
                    .groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) })
                    .mapNotNull { (source, ids) -> stores.source(source)?.let { source to encode(it, ids) } }
                    .toMap()
            tasks.keys.done(WORK, taken)
            encoded
        }

    /**
     * The texts of the casts of the works [ids] of the [store] not encoded yet, encoded now; the works that had any.
     */
    private fun encode(store: SourceStore, ids: Collection<String>): List<String> {
        val facet = WorkCasts.facetOf(store) ?: return emptyList()
        val entries = store.items.facetsOf(ids).mapValues { it.value[facet].orEmpty() }
        val texts =
            WorkCasts.of(store, facet, entries).mapValues { (_, cast) ->
                plugins.castReaders.flatMap { it.encoded(cast) }.distinct()
            }
        val missing = phrases.missing(store, texts.values.flatten())
        if (missing.isNotEmpty()) {
            val started = System.currentTimeMillis()
            phrases.of(store, missing)
            log.info(
                "{}: {} names of the casts of {} works encoded in {} ms",
                store.id,
                missing.size,
                entries.size,
                System.currentTimeMillis() - started,
            )
        }
        val fresh = missing.toHashSet()
        return texts.filterValues { list -> list.any { it in fresh } }.keys.toList()
    }

    private companion object {
        val WHAT = TaskKind.CAST_NAMES

        /** One work of every source: their works told apart by their keys ("<source>\t<id>"). */
        val WORK = Task(WHAT)

        const val SEP = '\t'

        /** After the start's own steps: the texts of the works and their vectors first. */
        const val START_DELAY_SECONDS = 90L

        /** A few seconds after the last capture of a series of pages. */
        const val QUIET_SECONDS = 10L
    }
}
