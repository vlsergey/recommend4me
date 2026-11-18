package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.CancellationException
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
    events: ApplicationEventPublisher,
    private val workload: Workload,
    private val chain: FacetChain,
    vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
    private val kept: KeptModelValues,
    private val settings: Settings,
) {
    private val fitting = FacetFitting(FacetReading(passes, chain, vocabularies), FacetScoring(kept, events))

    /**
     * Something the source's models learn from changed — the [reason], a key the interface words, told in the queue of
     * the works till the refresh begins: the source is refreshed [DELAY_SECONDS] after the first change of a series,
     * not put off by the next ones. A refresh under way is not given up: it goes on to its end, and the one planned
     * then works on all that came meanwhile (user, 2026-11-18: of the fitting given up a tenth of a second after its
     * start at every page a re-read of ficbook's kept pages brought, never ending while it lasted).
     */
    fun changed(source: String, reason: String) {
        val store = stores.source(source) ?: return
        if (chain.facetsOf(store).isEmpty()) return
        val work = refreshing(source)
        val due = if (workload.planned().any { it.work == work }) null else Instant.now().plusSeconds(DELAY_SECONDS)
        planRefresh(store, due, reason)
    }

    /**
     * The refresh of every facet of the [store] planned for [due] for the [reason] — whole in the queue of the
     * computations, the works read with it, a portion at a time.
     */
    private fun planRefresh(store: SourceStore, due: Instant?, reason: String) =
        workload.plan(refreshing(store.id), due, reason) { refreshed(store) }

    /**
     * On the start: what was worked out of facets a source no longer has the model work on forgotten — only when its
     * facets are others than the last start kept ([FACETS]): a pass over every value of every work, never "just in
     * case" (user, 2026-11-18) —; what the last run left undone of the fitting planned again.
     */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            val suggested = chain.facetsOf(s).map { it.key }
            val inferred = s.schema.facets.filter { it.infer }.map { it.key }
            val facets = (suggested.map { "s:$it" } + inferred.map { "i:$it" }).sorted().joinToString(",")
            val name = "$FACETS${s.id}"
            if (settings.get(name) != facets) {
                s.suggestions.keepOnly(suggested)
                s.modelValues.keepOnly(inferred)
                settings.set(name, facets)
            }
        }
        // What the last run of the application left undone of the fitting, planned again
        workload.resume("suggesters") { left, reason ->
            left.work.source?.let(stores::source)?.let { planRefresh(it, null, reason) }
        }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the chain. For a caller
     * that waits for it: carried through whatever changes meanwhile; those are refreshed after their quiet. A work of
     * its own, never joined to the background's alike.
     */
    fun refresh(store: SourceStore) =
        workload.run(refreshing(store.id), mapOf("asked" to 1), joinAlike = false) { refreshed(store) }

    /**
     * The model of every facet of the [store] fitted again, down the chain; given up only as the application stops
     * ([Workload.stopping]), with a [CancellationException]. How long each step took told to the log.
     *
     * ONE WORK OF THE QUEUE, EVERY WORK READ A PORTION AT A TIME ([WorkPasses.forEachPortion]): no pass holds more than
     * [TaskPortions.MOST] works (user, 2026-11-17). The values the models of their own give every work kept first; then
     * a facet after another, each walking the works as often as its value side, its fitting and its chances need
     * ([FacetFitting]) — the values the model gives of each kept as its chances are made ([KeptModelValues]), and read
     * by the facets after it as their context: the chain is not made again for every portion. The works whose values
     * changed are told as each portion is done ([FacetChancesChanged]).
     */
    private fun refreshed(store: SourceStore) {
        val encoder = plugins.textEncoder() ?: return
        val stop = { workload.stopping }
        val facets = chain.facetsOf(store)
        val started = System.currentTimeMillis()
        keepOwnModels(store, facets, stop)
        val own = System.currentTimeMillis()
        val characters = passes.charactersRead(store)
        val read = System.currentTimeMillis()
        val took =
            facets.indices.map { k ->
                val began = System.currentTimeMillis()
                fitting.fit(store, facets, k, plugins.suggester(store.id, facets[k].key), encoder, characters, stop)
                "${facets[k].key} ${System.currentTimeMillis() - began}"
            }
        log.info(
            "{}: the models of every facet fitted in {} ms: the values of the models of their own kept {}, the " +
                "dictionary's characters read {}, the facets {}",
            store.id,
            System.currentTimeMillis() - started,
            own - started,
            read - own,
            took,
        )
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
            if (stop()) throw CancellationException("${store.id}: the models of their own given up: the app stops")
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
        val characters = passes.charactersRead(store)
        fitting.fit(store, facets, k, suggester, encoder, characters, { workload.stopping }, anew = true)
    }

    /** Whether the facet's suggester is to be fitted anew, or being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = workload.due(fittingAnew(store, facetKey))

    companion object {
        /** The setting of a source's facets the last start kept, by the source: "s:<suggested>,i:<inferred>,…". */
        private const val FACETS = "suggesters.facets."

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
