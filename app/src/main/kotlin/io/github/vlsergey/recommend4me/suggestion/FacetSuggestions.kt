package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.laps.Laps
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(FacetSuggestions::class.java)

/** The model gave items of a source other values of a facet it works out: what reads them reads them again. */
data class FacetChancesChanged(val source: String, val ids: List<String>)

/** The chances of works, made in memory when they are read ([FacetSuggestions.chances]): item to facet to value to chance. */
typealias WorkChances = Map<String, Map<String, Map<String, Chance>>>

/**
 * WHAT THE MODEL SAYS OF THE VALUES OF FACETS, by the suggester plugin, for every facet a source
 * marks [suggest][FacetDef.suggest] or [infer][FacetDef.infer]: the chance of every value a work
 * has, and of every value it lacks that it more likely has than not.
 *
 * ONLY THE MODEL OF EACH FACET IS KEPT ([SuggestionRepository]): its fitted state and the values it
 * chooses from. THE CHANCES OF THE WORKS ARE NOT (user, 2026-10-15): whoever reads works — a page, a
 * card, a filter over the catalogue, the search index, the training of the grades — gets them made
 * in memory of those works alone, from their vectors and values, by the models kept ([chances]).
 * Nothing waits for a refit, nothing is written of a work.
 *
 * THE FACETS ARE WORKED OUT IN A CHAIN, in the order of [facetsOf]: each reads as its context the
 * values the site and the user give of every other facet, and the values the model gives of the
 * facets before it, made in the same pass — a work's characters are those of the universes given
 * before them, its pairings pairs of the characters given before them. The fitting and the reading
 * follow the same chain: a facet is judged on what it was fitted on.
 *
 * The models are fitted again in the background, in the queue of the computations, whenever
 * anything they learn from changed (the fingerprint of the whole facet); a fitting under way is
 * given up for a newer change and made again after it. Of the data nothing is kept between
 * operations; only the work waiting: the sources with changes, how many changes each had.
 */
@Service
class FacetSuggestions(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val settings: Settings,
    private val workload: Workload,
    models: ObjectProvider<FacetModel>,
    /** The card, when the launch asks for it: the inputs of a pass of every work put there for its facets ([WorksRead]). */
    cards: ObjectProvider<CardSource>,
) {
    private val vocabularies = FacetVocabularies(stores)
    private val facetChain = FacetChain(stores, plugins, models, cards, vocabularies)
    private val candidates = WorkCandidates(stores, plugins, facetChain, vocabularies)

    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "suggestions").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** How many changes each source has had ([changed]): a refresh begun before the last one is given up. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** The facets the model works on, in the order of the chain ([FacetChain.facetsOf]). */
    fun facetsOf(store: SourceStore): List<FacetDef> = facetChain.facetsOf(store)

    @EventListener
    fun itemsChanged(event: ItemsChanged) = changed(event.source, "items")

    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) = changed(event.source, "texts")

    @EventListener
    fun partsSaved(event: PartsSaved) = changed(event.source, "parts")

    /** The user's answer on a work's value: what the models of the facets learn from. */
    @EventListener
    fun answered(event: FacetAnswered) = changed(event.source, "answer")

    /**
     * Something the source's models learn from changed — the [reason], a key the interface words,
     * told in the queue of the works till the refresh begins: the source is refreshed after the
     * quiet that follows. A refresh under way is given up: it works on what it read before the
     * change, and the one after the quiet works on all of it.
     */
    private fun changed(source: String, reason: String) {
        val store = stores.source(source) ?: return
        if (facetsOf(store).isEmpty()) return
        generationOf(source).incrementAndGet()
        waiting += source
        val scheduling = scheduled.compareAndSet(false, true)
        val due = if (scheduling) Instant.now().plusSeconds(DELAY_SECONDS) else null
        facetsOf(store).forEach { workload.plan(refreshing(source, it), due, reason) }
        if (scheduling) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    /** The refresh of a facet of the source, as the queue of the works tells it planned and running. */
    private fun refreshing(source: String, facet: FacetDef) = Work("suggester", source = source, facet = facet.key)

    /** How many changes the source has had: a refresh begun before the last one is of stale data. */
    private fun generationOf(source: String): AtomicLong = generations.computeIfAbsent(source) { AtomicLong() }

    private fun drain() {
        scheduled.set(false)
        waiting.toList().forEach { source ->
            waiting -= source
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, givenUp = true, null)
            } catch (e: CancellationException) {
                log.info("{}: chances given up for newer changes, made again after the quiet", source)
            } catch (e: Exception) {
                log.warn("{}: chances were not made: {}", source, e.message, e)
            }
        }
    }

    @Order(4)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // What was worked out of facets a source no longer has the model work on is forgotten
        stores.sources.forEach { s -> s.suggestions.keepOnly(facetsOf(s).map { it.key }) }
        worker.execute {
            stores.sources.filter { facetsOf(it).isNotEmpty() }.forEach { s ->
                try {
                    refresh(s, givenUp = true, mapOf("start" to 1))
                } catch (e: CancellationException) {
                    log.info("{}: chances given up on start for newer changes, made again after the quiet", s.id)
                } catch (e: Exception) {
                    log.error("{}: chances were not made on start", s.id, e)
                }
            }
        }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the
     * chain. For a caller that waits for it: carried through whatever changes meanwhile; those are
     * refreshed after their quiet.
     */
    fun refresh(store: SourceStore) = refresh(store, givenUp = false, mapOf("asked" to 1))

    /**
     * [refresh]; [givenUp] — with a [CancellationException] once the source changes again
     * ([changed]): the background's; [reasons] — what it is made for, told in the queue of the works
     * beside those every facet was planned for ([changed]); null — those alone.
     *
     * Every facet a work of the queue of its own; the chances of every work of the facets before,
     * made in the pass, are the context of the next — held for the pass alone. The works whose given
     * values a refit changed are told once at its end ([FacetChancesChanged]).
     */
    private fun refresh(store: SourceStore, givenUp: Boolean, reasons: Map<String, Int>?) {
        val encoder = plugins.textEncoder() ?: return
        val generation = generationOf(store.id).get()
        val stop = if (givenUp) ({ generationOf(store.id).get() != generation }) else ({ false })
        val facets = facetsOf(store)
        val made = facetChain.seeded(store, null)
        val changedIds = LinkedHashSet<String>()
        facets.forEachIndexed { k, facet ->
            val work = refreshing(store.id, facet)
            val planned = workload.take(work)
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEachIndexed
            workload.run(work, reasons.orEmpty().toMutableMap().apply { planned.forEach { (key, n) -> merge(key, n, Int::plus) } }) {
                lockOf(store, facet).withLock { changedIds += fitFacet(store, facets, k, suggester, encoder, made, stop) }
            }
        }
        if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds.toList()))
    }

    /**
     * The facet's suggester fitted again FROM SCRATCH — every setting tried anew, not a short walk on
     * from the state fitted last: the user asked for it on the page of the models. Its context, the
     * facets before it in the chain, made of every work first. In the background; false when the
     * source has no such facet.
     */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }.takeIf { it >= 0 } ?: return false
        val facet = facets[k]
        if (plugins.suggester(store.id, facet.key) == null) return false
        val encoder = plugins.textEncoder() ?: return false
        val id = "${store.id}/${facet.key}"
        searching += id
        worker.execute {
            try {
                // The suggester named when it runs: one chosen meanwhile is the one fitted
                val suggester = plugins.suggester(store.id, facet.key) ?: return@execute
                val changedIds = workload.run(Work("suggesterAnew", source = store.id, facet = facet.key), mapOf("asked" to 1), asked = true) {
                    lockOf(store, facet).withLock { fitFacet(store, facets, k, suggester, encoder, facetChain.chain(store, null, k), { false }, anew = true) }
                }
                if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds))
            } catch (e: Exception) {
                log.warn("{}: the suggester of {} was not fitted anew: {}", store.id, facet.key, e.message, e)
            } finally {
                searching -= id
            }
        }
        return true
    }

    /**
     * The suggester [method] made the one the facet learns with, and the facet fitted with it from
     * scratch ([searchAnew]); false when there is no such suggester or facet.
     */
    fun chooseMethod(store: SourceStore, facetKey: String, method: String): Boolean {
        if (plugins.suggesters.none { it.id == method } || facetsOf(store).none { it.key == facetKey }) return false
        settings.set(Plugins.suggesterSetting(store.id, facetKey), method)
        return searchAnew(store, facetKey)
    }

    /** Everything the suggesters of the facet learn from, read now ([FacetChain.task]). */
    fun task(store: SourceStore, facetKey: String): SuggestionTask? = facetChain.task(store, facetKey)

    /** The facets being fitted anew now ([searchAnew]), by "source/facet": the work under way, nothing of the data. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether the facet's suggester is being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = "${store.id}/$facetKey" in searching

    /** One lock a facet: a fitting of one facet does not wait for a long fitting of another. */
    private fun lockOf(store: SourceStore, facet: FacetDef) = locks.computeIfAbsent("${store.id}/${facet.key}") { ReentrantLock() }

    /** The chances of the works [ids] made now down the chain ([FacetChain.chances]). */
    fun chances(store: SourceStore, ids: Collection<String>?, through: String? = null, laps: Laps = Laps(), every: EveryItemFacets? = null): WorkChances =
        facetChain.chances(store, ids, through, laps, every)

    /** The model's chance of one value for every work of [ids] ([FacetChain.chancesOfValue]). */
    fun chancesOfValue(store: SourceStore, ids: Collection<String>, facet: String, key: String): Map<String, Double> = facetChain.chancesOfValue(store, ids, facet, key)

    /** The model's weight of every value of the [facet] over the works as a whole ([FacetChain.weights]). */
    fun weights(store: SourceStore, facet: String): Map<String, Double>? = facetChain.weights(store, facet)

    /** [chances] of the [wanted] facets alone ([FacetChain.chancesOf]). */
    fun chancesOf(store: SourceStore, ids: Collection<String>?, wanted: Collection<String>, laps: Laps = Laps(), every: EveryItemFacets? = null): WorkChances =
        facetChain.chancesOf(store, ids, wanted, laps, every)

    /**
     * The model of the [k]-th of the [facets] fitted again when what it learns from changed — every
     * setting tried anew when [anew], the user asked for it ([searchAnew]) — and every work's chances
     * of it made into [made], the context of the facets after it. [stop] is asked between the
     * stages, and by the fitting between its steps: newer data came, and the work is given up with a
     * [CancellationException] before anything made of the old is kept. Answers the works whose
     * given values a refit changed: every work scored by the model before and after it.
     */
    private fun fitFacet(
        store: SourceStore, facets: List<FacetDef>, k: Int, suggester: FacetSuggester, encoder: TextEncoder, made: MadeChances, stop: () -> Boolean,
        anew: Boolean = false,
    ): List<String> = facetChain.worksRead(store, encoder, null, card = true).use { works -> fitFacet(store, facets, k, suggester, encoder, made, stop, anew, works) }

    /** [fitFacet] of the works read for it, [works]: their inputs on the card for the fitting and the scoring after it. */
    private fun fitFacet(
        store: SourceStore, facets: List<FacetDef>, k: Int, suggester: FacetSuggester, encoder: TextEncoder, made: MadeChances, stop: () -> Boolean,
        anew: Boolean, works: WorksRead,
    ): List<String> {
        val facet = facets[k]
        fun checkpoint() {
            if (stop()) throw CancellationException("${store.id}: ${facet.key} given up for newer changes")
        }
        val started = System.currentTimeMillis()
        val vocabulary = vocabularies.vocabularyOf(store, facet, null, made, works.characters, works.answers, site = works.site)
        val vocabularyMillis = System.currentTimeMillis() - started
        checkpoint()
        val data = facetChain.load(store, facet, encoder, vocabulary, null, made, null, works)
        val read = System.currentTimeMillis() - started
        checkpoint()
        // The whole task kept in a file when asked for: its fitting made again and measured apart from the application
        System.getenv(TASKS_FOLDER)?.let { dir -> SuggestionTaskFile.write(data.task, Path.of(dir, "${store.id}-${facet.key}.task.gz")) }
        if (data.values.isEmpty()) return emptyList()
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, data.task) }
        val unpacked = System.currentTimeMillis() - started
        checkpoint()
        val current = !anew && kept != null && stored.basis == data.basis
        val fitted = if (current) {
            // Fitted before the values it chooses from, or its scoring, or the values' weights in it, were kept: kept now, not fitted again
            val scoring = store.suggestions.scoring(facet.key)
            if (stored.values != data.values || scoring == null || suggester.weights(scoring.bytes) == null) {
                store.suggestions.keepValues(facet.key, data.values, kept.scoring(data.task))
            }
            kept
        } else {
            // From the state fitted last when there is one: the data moved a little since
            val fitStarted = System.currentTimeMillis()
            val fresh = suggester.fit(data.task, if (anew) null else kept, stop) ?: return emptyList()
            checkpoint()
            val fit = SuggesterFit(fresh.report, System.currentTimeMillis() - fitStarted, data.ids.size, data.values.size)
            store.suggestions.saveSuggester(facet.key, suggester.id, Instant.now(), data.basis, fresh.pack(), data.values, fresh.scoring(data.task), fit)
            log.info("{}: the suggester of {} fitted on {} items, {} values in {} ms, {} of them reading", store.id, facet.key, data.ids.size, data.values.size, System.currentTimeMillis() - started, read)
            fresh
        }
        val fittedAt = System.currentTimeMillis() - started
        val after = fitted.on(data.task).use { facetChain.keep(made, facet, data, it, compare = true) }
        val scored = System.currentTimeMillis() - started
        // What the model before the refit gave alone: scored again, nothing of it kept
        val changedIds = if (current || !facet.infer) emptyList() else {
            val before = kept?.let { k0 -> k0.on(data.task).use { facetChain.keep(MadeChances(), facet, data, it, compare = true) } }
            data.ids.filter { id -> before == null || before[id].orEmpty() != after[id].orEmpty() }
        }
        // How many values the model gives the works: a model that gives one work dozens tells of itself
        val most = after.maxByOrNull { it.value.size }
        log.info(
            "{}: chances of {} of {} items made in memory in {} ms — the dictionary {}, reading {}, the weights {}, fitting {}, scoring {}; {} works given other values; " +
                "{} values given in all, {} works given more than {}, the most {} to {}",
            store.id, facet.key, data.ids.size, System.currentTimeMillis() - started,
            vocabularyMillis, read - vocabularyMillis, unpacked - read, fittedAt - unpacked, scored - fittedAt, changedIds.size,
            after.values.sumOf { it.size }, after.values.count { it.size > MANY_GIVEN }, MANY_GIVEN, most?.value?.size ?: 0, most?.key,
        )
        return changedIds
    }

    /** The values to suggest for an item ([WorkCandidates.ofItem]). */
    fun ofItem(store: SourceStore, itemId: String, chances: Map<String, Map<String, Chance>>? = null): List<ItemFacetSuggestions> = candidates.ofItem(store, itemId, chances)

    /** What the passages of the item naming the [value] are found by ([WorkCandidates.quoting]). */
    fun quoting(store: SourceStore, itemId: String, facet: String, value: String): Quoting? = candidates.quoting(store, itemId, facet, value)

    /** Every value of the [facet] the item may be given ([WorkCandidates.candidates]). */
    fun candidates(store: SourceStore, itemId: String, facet: String, limit: Int? = null, named: Int? = null): ItemFacetCandidates? =
        candidates.candidates(store, itemId, facet, limit, named)

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The quiet after the last change before a source is refreshed: a page sends itself several times as it grows. */
        private const val DELAY_SECONDS = 5L

        /** The environment variable naming the folder every task of a whole facet is kept in ([SuggestionTaskFile]); unset, none is. */
        private const val TASKS_FOLDER = "RECOMMEND4ME_TASKS"

        /** How many values given to one work the log of a refit counts the works above: a sign of a model gone wide, not a rule. */
        private const val MANY_GIVEN = 10
    }
}
