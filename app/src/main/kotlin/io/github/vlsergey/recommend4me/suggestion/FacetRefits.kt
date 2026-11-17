package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

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
    events: ApplicationEventPublisher,
    private val workload: Workload,
    private val chain: FacetChain,
    vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
    private val kept: KeptModelValues,
) {
    private val fitting = FacetFitting(FacetReading(passes, chain, vocabularies), FacetScoring(kept, events))

    /** How many changes each source has had ([changed]): a refresh begun before the last one is given up. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Something the source's models learn from changed — the [reason], a key the interface words, told in the queue of
     * the works till the refresh begins: the source is refreshed [DELAY_SECONDS] after the first change of a series,
     * not put off by the next ones. A refresh under way is given up: it works on what it read before the change, and
     * the one planned works on all of it.
     */
    fun changed(source: String, reason: String) {
        val store = stores.source(source) ?: return
        if (chain.facetsOf(store).isEmpty()) return
        generationOf(source).incrementAndGet()
        val work = refreshing(source)
        val due = if (workload.planned().any { it.work == work }) null else Instant.now().plusSeconds(DELAY_SECONDS)
        planRefresh(store, due, reason)
    }

    /**
     * The refresh of every facet of the [store] planned for [due] for the [reason], given up for a newer change — whole
     * in the queue of the computations, the works read with it, a portion at a time.
     */
    private fun planRefresh(store: SourceStore, due: Instant?, reason: String) =
        workload.plan(refreshing(store.id), due, reason) { refreshed(store, givenUp = true) }

    /** How many changes the source has had: a refresh begun before the last one is of stale data. */
    private fun generationOf(source: String): AtomicLong = generations.computeIfAbsent(source) { AtomicLong() }

    /**
     * Every source refreshed on the start, in the background, each a work of the queue; what was worked out of facets a
     * source no longer has the model work on is forgotten.
     */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            s.suggestions.keepOnly(chain.facetsOf(s).map { it.key })
            s.modelValues.keepOnly(s.schema.facets.filter { it.infer }.map { it.key })
        }
        stores.sources.filter { chain.facetsOf(it).isNotEmpty() }.forEach { planRefresh(it, null, "start") }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the chain. For a caller
     * that waits for it: carried through whatever changes meanwhile; those are refreshed after their quiet. A work of
     * its own, never joined to the background's alike, which a newer change gives up.
     */
    fun refresh(store: SourceStore) =
        workload.run(refreshing(store.id), mapOf("asked" to 1), joinAlike = false) {
            refreshed(store, givenUp = false)
        }

    /**
     * The model of every facet of the [store] fitted again, down the chain. [givenUp] — with a [CancellationException]
     * once the source changes again ([changed]): the background's.
     *
     * ONE WORK OF THE QUEUE, EVERY WORK READ A PORTION AT A TIME ([WorkPasses.forEachPortion]): no pass holds more than
     * [TaskPortions.MOST] works (user, 2026-11-17). The values the models of their own give every work kept first; then
     * a facet after another, each walking the works as often as its value side, its fitting and its chances need
     * ([FacetFitting]) — the values the model gives of each kept as its chances are made ([KeptModelValues]), and read
     * by the facets after it as their context: the chain is not made again for every portion. The works whose values
     * changed are told as each portion is done ([FacetChancesChanged]).
     */
    private fun refreshed(store: SourceStore, givenUp: Boolean) {
        val encoder = plugins.textEncoder() ?: return
        val generation = generationOf(store.id).get()
        val stop = if (givenUp) ({ generationOf(store.id).get() != generation }) else ({ false })
        val facets = chain.facetsOf(store)
        keepOwnModels(store, facets, stop)
        val characters = passes.charactersRead(store)
        facets.indices.forEach { k ->
            fitting.fit(store, facets, k, plugins.suggester(store.id, facets[k].key), encoder, characters, stop)
        }
    }

    /**
     * Of every work, a portion at a time, the values the facets worked out by models of their own give it kept
     * ([FacetModel], [KeptModelValues]): what the facets of the chain read of them, as of the drafts.
     */
    private fun keepOwnModels(store: SourceStore, facets: List<FacetDef>, stop: () -> Boolean) {
        val chained = facets.map { it.key }.toSet()
        val own = store.schema.facets.filter { it.infer && it.key !in chained }
        if (own.isEmpty()) return
        passes.forEachPortion(store) { ids ->
            if (stop()) throw CancellationException("${store.id}: the models of their own given up for newer changes")
            val known = EveryItemFacets(store.items.facetsOf(ids), store.corrections.facetsOf(ids))
            kept.write(store, ids, known.site, chain.seeded(store, ids, every = known), own)
        }
    }

    /**
     * The facet's suggester fitted again FROM SCRATCH — every setting tried anew, not a short walk on from the state
     * fitted last: the user asked for it on the page of the models, and it goes ahead of the background's works. Its
     * context, the facets before it in the chain, as the last pass kept them. In the background; false when the source
     * has no such facet.
     */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }
        val encoder = plugins.textEncoder()
        if (k < 0 || plugins.suggester(store.id, facetKey) == null || encoder == null) return false
        workload.plan(
            fittingAnew(store, facetKey),
            null,
            "asked",
            asked = true,
            // The facets after it in the chain read its values: a pass of every one of them, the values kept with it
            after = { _: Unit -> changed(store.id, "chances") },
        ) {
            fittedAnew(store, facets, k, encoder)
        }
        return true
    }

    /** The [k]-th of the [facets] fitted anew ([searchAnew]). */
    private fun fittedAnew(store: SourceStore, facets: List<FacetDef>, k: Int, encoder: TextEncoder) {
        // The suggester named when it runs: one chosen meanwhile is the one fitted
        val suggester = plugins.suggester(store.id, facets[k].key) ?: return
        fitting.fit(store, facets, k, suggester, encoder, passes.charactersRead(store), { false }, anew = true)
    }

    /** Whether the facet's suggester is to be fitted anew, or being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = workload.due(fittingAnew(store, facetKey))

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

        /** The refresh of the facets of the source, as the queue of the works tells it planned and running. */
        private fun refreshing(source: String) = Work("suggesters", source = source)

        /** The fitting anew of a facet of the source ([searchAnew]), as the queue of the works tells it. */
        private fun fittingAnew(store: SourceStore, facetKey: String) =
            Work("suggesterAnew", source = store.id, facet = facetKey)
    }
}
