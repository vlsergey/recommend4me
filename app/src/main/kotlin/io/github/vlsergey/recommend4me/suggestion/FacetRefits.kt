package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(FacetRefits::class.java)

/**
 * THE MODELS OF A SOURCE'S FACETS FITTED AGAIN, in the background, in the queue of the computations, down the chain
 * ([FacetChain]): the fitting and the reading follow the same chain, a facet is judged on what it was fitted on.
 *
 * They are fitted again whenever anything they learn from changed ([FacetRefitEvents]; the fingerprint of the whole
 * facet); a fitting under way is given up for a newer change and made again after it. And from scratch when the user
 * asks for it ([searchAnew]). Of the data nothing is kept between operations; only the work waiting: the sources with
 * changes, how many changes each had.
 */
@Service
internal class FacetRefits(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
    private val chain: FacetChain,
    vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
) {
    private val fitting = FacetFitting(chain, vocabularies)

    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "suggestions").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    /** How many changes each source has had ([changed]): a refresh begun before the last one is given up. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** The facets being fitted anew now ([searchAnew]), by "source/facet": the work under way, nothing of the data. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Something the source's models learn from changed — the [reason], a key the interface words, told in the queue of
     * the works till the refresh begins: the source is refreshed after the quiet that follows. A refresh under way is
     * given up: it works on what it read before the change, and the one after the quiet works on all of it.
     */
    fun changed(source: String, reason: String) {
        val store = stores.source(source) ?: return
        if (chain.facetsOf(store).isEmpty()) return
        generationOf(source).incrementAndGet()
        waiting += source
        val scheduling = scheduled.compareAndSet(false, true)
        val due = if (scheduling) Instant.now().plusSeconds(DELAY_SECONDS) else null
        chain.facetsOf(store).forEach { workload.plan(refreshing(source, it), due, reason) }
        if (scheduling) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    /** The refresh of a facet of the source, as the queue of the works tells it planned and running. */
    private fun refreshing(source: String, facet: FacetDef) = Work("suggester", source = source, facet = facet.key)

    /** How many changes the source has had: a refresh begun before the last one is of stale data. */
    private fun generationOf(source: String): AtomicLong = generations.computeIfAbsent(source) { AtomicLong() }

    /**
     * Every source waiting refreshed, one after another. A boundary of the background: whatever fails of one source is
     * told in the log, and the others are refreshed all the same.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        scheduled.set(false)
        waiting.toList().forEach { source ->
            waiting -= source
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, givenUp = true, null)
            } catch (e: CancellationException) {
                log.info("{}: chances given up for newer changes, made again after the quiet: {}", source, e.message)
            } catch (e: Exception) {
                log.warn("{}: chances were not made: {}", source, e.message, e)
            }
        }
    }

    /**
     * Every source refreshed on the start, in the background; what was worked out of facets a source no longer has the
     * model work on is forgotten. A boundary of the background: whatever fails of one source is told in the log, and
     * the others are refreshed all the same.
     */
    @Suppress("TooGenericExceptionCaught")
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s -> s.suggestions.keepOnly(chain.facetsOf(s).map { it.key }) }
        worker.execute {
            stores.sources
                .filter { chain.facetsOf(it).isNotEmpty() }
                .forEach { s ->
                    try {
                        refresh(s, givenUp = true, mapOf("start" to 1))
                    } catch (e: CancellationException) {
                        log.info(
                            "{}: chances given up on start for newer changes, made again after the quiet: {}",
                            s.id,
                            e.message,
                        )
                    } catch (e: Exception) {
                        log.error("{}: chances were not made on start", s.id, e)
                    }
                }
        }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the chain. For a caller
     * that waits for it: carried through whatever changes meanwhile; those are refreshed after their quiet.
     */
    fun refresh(store: SourceStore) = refresh(store, givenUp = false, mapOf("asked" to 1))

    /**
     * [refresh]; [givenUp] — with a [CancellationException] once the source changes again ([changed]): the
     * background's; [reasons] — what it is made for, told in the queue of the works beside those every facet was
     * planned for ([changed]); null — those alone.
     *
     * Every facet a work of the queue of its own; the chances of every work of the facets before, made in the pass, are
     * the context of the next — held for the pass alone. Every work read once for the pass, its inputs on the card till
     * its end: the facets read alike, not each facet reading all again. The works whose given values a refit changed
     * are told once at its end ([FacetChancesChanged]).
     */
    private fun refresh(store: SourceStore, givenUp: Boolean, reasons: Map<String, Int>?) {
        val encoder = plugins.textEncoder() ?: return
        val generation = generationOf(store.id).get()
        val stop = if (givenUp) ({ generationOf(store.id).get() != generation }) else ({ false })
        val facets = chain.facetsOf(store)
        val changedIds = LinkedHashSet<String>()
        passes.worksRead(store, encoder, null, card = true).use { works ->
            val made = chain.seeded(store, null, works.drafts, EveryItemFacets(works.site, works.corrections))
            facets.forEachIndexed { k, facet ->
                val work = refreshing(store.id, facet)
                val planned = workload.take(work)
                val suggester = plugins.suggester(store.id, facet.key) ?: return@forEachIndexed
                workload.run(
                    work,
                    reasons.orEmpty().toMutableMap().apply { planned.forEach { (key, n) -> merge(key, n, Int::plus) } },
                ) {
                    changedIds += fitting.fit(store, facets, k, suggester, encoder, made, stop, works)
                }
            }
        }
        if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds.toList()))
    }

    /**
     * The facet's suggester fitted again FROM SCRATCH — every setting tried anew, not a short walk on from the state
     * fitted last: the user asked for it on the page of the models. Its context, the facets before it in the chain,
     * made of every work first. In the background; false when the source has no such facet.
     */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }
        val encoder = plugins.textEncoder()
        if (k < 0 || plugins.suggester(store.id, facetKey) == null || encoder == null) return false
        val id = "${store.id}/$facetKey"
        searching += id
        worker.execute { fitAnew(store, facets, k, encoder, id) }
        return true
    }

    /**
     * The [k]-th of the [facets] fitted anew ([searchAnew]), on the worker; [id] — its mark among those [searching],
     * taken off at the end. A boundary of the background: whatever fails is told in the log.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fitAnew(store: SourceStore, facets: List<FacetDef>, k: Int, encoder: TextEncoder, id: String) {
        val facet = facets[k]
        try {
            // The suggester named when it runs: one chosen meanwhile is the one fitted
            val suggester = plugins.suggester(store.id, facet.key) ?: return
            val changedIds =
                workload.run(Work("suggesterAnew", source = store.id, facet = facet.key), mapOf("asked" to 1), true) {
                    // The context and the fitting of one reading of every work
                    passes.worksRead(store, encoder, null, card = true).use { works ->
                        val made = chain.chain(store, encoder, works, k)
                        fitting.fit(store, facets, k, suggester, encoder, made, { false }, works, anew = true)
                    }
                }
            if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds))
        } catch (e: Exception) {
            log.warn("{}: the suggester of {} was not fitted anew: {}", store.id, facet.key, e.message, e)
        } finally {
            searching -= id
        }
    }

    /** Whether the facet's suggester is being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = "${store.id}/$facetKey" in searching

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /**
         * The quiet after the last change before a source is refreshed: a page sends itself several times as it grows.
         */
        private const val DELAY_SECONDS = 5L

        /**
         * The place of the start's refresh among the listeners of the application's start: after the texts' vectors
         * ([TextVectors][io.github.vlsergey.recommend4me.textvector.TextVectors], 3), which the models read.
         */
        private const val START_ORDER = 4
    }
}
