package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.api.model.WorkStep
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.WorkKeys
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
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
    private val settings: Settings,
) {
    private val fitting = FacetFitting(FacetReading(passes, chain, vocabularies), FacetScoring())

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
        val work = refreshing(store.type)
        // The source kept waiting in the queue: of every source of the type one work, of the sources changed
        workload.keys.want(work, listOf(store.id))
        val due = if (workload.planned().any { it.work == work }) null else Instant.now().plusSeconds(DELAY_SECONDS)
        planRefresh(store.type, due, reason)
    }

    /**
     * The refresh of the facets of every source of the [type] the queue keeps waiting ([WorkKeys.wanted]), planned for
     * [due] for the [reason] — ONE WORK OF THE TYPE, the models of every field of its works (owner, 2026-11-19: not a
     * work a source), whole in the queue of the computations, the works read with it, a portion at a time.
     */
    private fun planRefresh(type: String, due: Instant?, reason: String) =
        workload.plan(refreshing(type), due, reason) {
            val work = refreshing(type)
            val taken = workload.keys.wanted(work)
            val sources = stores.typeOf(type).sources.filter { it.id in taken }
            sources.forEachIndexed { i, s -> refreshed(s, i, sources.size) }
            workload.keys.done(work, taken)
        }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        // Of a work a source of before, that source
        workload.resumption.resumes(WorkKind.SUGGESTERS) { left, reason ->
            left.work.source?.let { changed(it, reason) } ?: left.work.type?.let { planRefresh(it, null, reason) }
        }
    }

    /**
     * On the start: what was worked out of facets a source no longer has the model work on forgotten — only when its
     * facets are others than the last start kept ([FACETS]): a pass over every value of every work, never "just in
     * case" (user, 2026-11-18).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            val suggested = chain.facetsOf(s).map { it.key }
            val inferred = s.schema.facets.filter { it.infer }.map { it.key }
            val facets = (suggested.map { "s:$it" } + inferred.map { "i:$it" }).sorted().joinToString(",")
            val name = "$FACETS${s.id}"
            if (settings.get(name) != facets) {
                s.suggestions.keepOnly(suggested)
                settings.set(name, facets)
            }
        }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the chain. For a caller
     * that waits for it: carried through whatever changes meanwhile; those are refreshed after their quiet. A work of
     * its own, never joined to the background's alike.
     */
    fun refresh(store: SourceStore) =
        workload.run(refreshing(store.type), mapOf("asked" to 1), joinAlike = false) { refreshed(store, 0, 1) }

    /**
     * The model of every facet of the [store] fitted again, down the chain; given up only as the application stops
     * ([Workload.stopping]), with a [CancellationException]. How long each step took told to the log.
     *
     * ONE WORK OF THE QUEUE, EVERY WORK READ A PORTION AT A TIME ([WorkPasses.forEachPortion]): no pass holds more than
     * [TaskPortions.MOST] works (user, 2026-11-17). A facet after another, each walking the works as often as its value
     * side, its fitting and its cutoff need ([FacetFitting]), the facets before it in the chain made now by their
     * models just fitted ([FacetChain.before]). Only the models are made: the values they give the works are filled
     * into the index of the list after them, last of the queue ([FacetModelsFitted]; user, 2026-11-18: "split every
     * such work in two: making new models and filling the works with the new computed data").
     */
    private fun refreshed(store: SourceStore, of: Int, sources: Int) {
        val encoder = plugins.textEncoder() ?: return
        val stop = { workload.stopping }
        val facets = chain.facetsOf(store)
        val started = System.currentTimeMillis()
        // What is done, told to the queue: every facet a step (user, 2026-11-18: "a progress indicator is needed")
        val steps = facets.size.coerceAtLeast(1).toDouble()
        workload.progress(of.toDouble() / sources, WorkStep.READ_CHARACTERS)
        val characters = passes.charactersRead(store)
        val read = System.currentTimeMillis()
        val took =
            facets.indices.map { k ->
                workload.progress(
                    (of + k / steps) / sources,
                    WorkStep.FIT_FACET,
                    I18nKeys.facetLabel(store.id, facets[k]),
                )
                val began = System.currentTimeMillis()
                fitting.fit(store, facets, k, plugins.suggester(store.id, facets[k].key), encoder, characters, stop)
                "${facets[k].key} ${System.currentTimeMillis() - began}"
            }
        log.info(
            "{}: the models of every facet fitted in {} ms: the dictionary's characters read {}, the facets {}",
            store.id,
            System.currentTimeMillis() - started,
            read - started,
            took,
        )
        events.publishEvent(FacetModelsFitted(store.id))
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
            // The facets after it in the chain read its values: every one of them fitted again, the values filled after
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

        /** The refresh of the facets of the type's sources, as the queue of the works tells it planned and running. */
        private fun refreshing(type: String) = Work(WorkKind.SUGGESTERS, type = type)

        /** The fitting anew of a facet of the source ([searchAnew]), as the queue of the works tells it. */
        private fun fittingAnew(store: SourceStore, facetKey: String) =
            Work(WorkKind.SUGGESTER_ANEW, source = store.id, facet = facetKey)
    }
}
