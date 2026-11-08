package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
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
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(CastNames::class.java)

/** The texts of the casts of these works of a source were encoded: the models that read them read them anew. */
data class CastNamesEncoded(val source: String, val ids: List<String>)

/**
 * THE TEXTS OF THE SAID CASTS ENCODED, IN THE BACKGROUND: of every work whose line changed, the texts every way of
 * reading the casts asks for ([CastReader.encoded]) — the names, the pairings written so — encoded and kept as the
 * short texts are ([PhraseVectors]), a text once, after a quiet; on start, of every work. In the queue of the
 * computations ([Workload]), never on the thread of a request: the models read the vectors kept alone ([CastReading]).
 * The queue of the works waiting is the only thing kept here.
 */
@Component
class CastNames(
    private val stores: Stores,
    private val plugins: Plugins,
    private val phrases: PhraseVectors,
    private val workload: Workload,
    private val events: ApplicationEventPublisher,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cast-names").apply { isDaemon = true }
    }

    /** Of every source, its works to encode the casts of; null of every work. Guarded by itself. */
    private val queued = HashMap<String, MutableSet<String>?>()

    /** The encoding planned, put off by every change till they are quiet; guarded by [queued]. */
    private var pending: ScheduledFuture<*>? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val sources = stores.sources.filter(::encodes)
        synchronized(queued) { sources.forEach { queued[it.id] = null } }
        sources.forEach { schedule(it.id, START_DELAY_SECONDS, "start") }
    }

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val store = stores.source(event.source)?.takeIf(::encodes) ?: return
        synchronized(queued) {
            if (store.id !in queued) queued[store.id] = HashSet()
            queued[store.id]?.addAll(event.ids)
        }
        schedule(store.id, QUIET_SECONDS, "items")
    }

    /** Whether a way of reading asks for texts of the source's casts at all: it has a line, and a way is installed. */
    private fun encodes(store: SourceStore): Boolean =
        WorkCasts.facetOf(store) != null && plugins.castReaders.isNotEmpty()

    private fun schedule(source: String, delaySeconds: Long, reason: String) {
        workload.plan(work(source), Instant.now().plusSeconds(delaySeconds), reason)
        synchronized(queued) {
            pending?.cancel(false)
            pending = worker.schedule(::drain, delaySeconds, TimeUnit.SECONDS)
        }
    }

    // The worker's loop: a source that fails any way is told in the log and leaves the others to be encoded
    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        val taken = synchronized(queued) { HashMap(queued).also { queued.clear() } }
        taken.forEach { (source, ids) ->
            val store = stores.source(source) ?: return@forEach
            try {
                val encoded = workload.run(work(source)) { encode(store, ids) }
                // Told out of the queue: whatever reads the works again plans its own work
                if (encoded.isNotEmpty()) events.publishEvent(CastNamesEncoded(source, encoded))
            } catch (e: Exception) {
                log.warn("{}: the names of the casts were not encoded: {}", source, e.message, e)
            }
        }
    }

    /**
     * The texts of the casts of the works [ids] (every work of the [store] when null) not encoded yet, encoded now; the
     * works that had any.
     */
    private fun encode(store: SourceStore, ids: Collection<String>?): List<String> {
        val facet = WorkCasts.facetOf(store) ?: return emptyList()
        val entries =
            if (ids == null)
                HashMap<String, List<String>>().also { m ->
                    store.items.forEachFacet(facet) { id, keys -> m[id] = keys }
                }
            else ids.associateWith { store.items.facets(it)[facet].orEmpty() }
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

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    private companion object {
        const val WHAT = "castNames"

        fun work(source: String) = Work(WHAT, source = source)

        /** After the start's own steps: the texts of the works and their vectors first. */
        const val START_DELAY_SECONDS = 90L

        /** A few seconds after the last capture of a series of pages. */
        const val QUIET_SECONDS = 10L
    }
}
