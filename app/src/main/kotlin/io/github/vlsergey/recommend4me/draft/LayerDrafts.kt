package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.universe.DictionaryLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseChangeEvent
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LayerDrafts::class.java)

/**
 * THE DRAFTS OF THE LAYER, MADE: of every work of a source that asks the catalogue for statements
 * ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]), what the source
 * drafts of it from its values on the site through the dictionary
 * ([Source.draft][io.github.vlsergey.recommend4me.source.Source.draft]) — and the universe of every character drafted
 * that is in one universe of the dictionary alone; and of every work of a source with fandoms not said by its site to
 * be of no universe, the universe every fandom of it is bound to
 * ([FandomRepository][io.github.vlsergey.recommend4me.universe.FandomRepository]) and every universe above it.
 *
 * Made again when the work is captured or refreshed — before anything reads it — and, of every work of the type, when
 * the dictionary of the universes changes ([UniverseChangeEvent]) and on start — these in the background, in the queue
 * of the computations ([Tasks]), never on the thread of the start or of a request: the works whose drafts changed are
 * then read again as any changed work is.
 *
 * A draft is a value the work is given, marked so
 * ([Chance.draftedFrom][io.github.vlsergey.recommend4me.suggestion.Chance.draftedFrom]); the user's answers override
 * it, kept apart: a draft taken away stays taken away, whatever it is made of again.
 */
@Component
class LayerDrafts(
    private val stores: Stores,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
) {

    /**
     * The sources to be drafted again whole — in the background, in the queue of the computations, after a quiet;
     * guarded by itself.
     */
    private val redrafted = LinkedHashSet<String>()

    /**
     * After the sites' tags are bound ([TagNormalization][io.github.vlsergey.recommend4me.tag.TagNormalization]),
     * before the model reads the works: the works written drafted at once — a few, before anything reads them.
     */
    @Order(ORDER)
    @EventListener
    fun itemsChanged(event: WorkChangeEvent) {
        val store = stores.source(event.source)?.takeIf(::drafts) ?: return
        if (event.ids.isEmpty()) return
        val lookup = DictionaryLookup.load(stores.typeOf(store.id).universes)
        // Of many works one streamed pass, not a thousand at a time: each IN of a thousand is a scan in H2
        val facets = store.items.facetsOf(event.ids)
        // The names of the values as written, of every facet of the works at once
        val keys = HashMap<String, HashSet<String>>()
        facets.values.forEach { of -> of.forEach { (facet, values) -> keys.getOrPut(facet) { HashSet() } += values } }
        val names = keys.mapValues { (facet, values) -> store.items.facetNames(facet, values) }
        redraft(store, lookup, event.ids, facets, names, store.drafts.ofItems(event.ids))
    }

    /** The dictionary of the universes changed: every work of every source that drafts drafted in the background. */
    @EventListener(UniverseChangeEvent::class)
    fun universesChanged() = stores.sources.filter(::drafts).forEach { redraftLater(it.id, "dictionary") }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(DRAFTING.what) { _, reason ->
            stores.sources.filter(::drafts).forEach { redraftLater(it.id, reason) }
        }
    }

    /**
     * Every work of the [source] drafted again once the askings are quiet for [QUIET_SECONDS] — a few in a row draft
     * once — the works whose drafts changed then read again.
     */
    private fun redraftLater(source: String, reason: String) {
        synchronized(redrafted) { redrafted += source }
        tasks.plan(DRAFTING, Instant.now().plusSeconds(QUIET_SECONDS), reason, after = ::told) { redrafted() }
    }

    /**
     * Every work of the sources planned drafted again now, in the queue of the computations: what the background does
     * after the quiet.
     */
    fun redraftPlanned() = told(tasks.run(DRAFTING) { redrafted() })

    /** Every work of the sources planned drafted again; of each, the works whose drafts changed. */
    @Suppress("TooGenericExceptionCaught")
    private fun redrafted(): List<Pair<String, List<String>>> =
        synchronized(redrafted) { redrafted.toList().also { redrafted.clear() } }
            .mapNotNull { source ->
                val store = stores.source(source)?.takeIf(::drafts) ?: return@mapNotNull null
                try {
                    source to redraftWhole(store)
                } catch (e: Exception) {
                    // A BOUNDARY: a source's drafting runs its plugin's code (Source.draft), which may fail in any
                    // way; the failure is logged and the other sources drafted
                    log.error("{}: the drafts of the works were not made", source, e)
                    null
                }
            }

    /** Told once out of the queue — whatever reads the works again plans its own work — the works [changed]. */
    private fun told(changed: List<Pair<String, List<String>>>) = changed.forEach { (source, ids) ->
        if (ids.isNotEmpty()) events.publishEvent(WorkChangeEvent(source, ids))
    }

    /**
     * Every work of the [store] drafted again: its values, their names and its drafts read once, a pass over each
     * table.
     */
    private fun redraftWhole(store: SourceStore): List<String> {
        val ids = store.items.keys().map { it.id }
        return redraft(
            store,
            DictionaryLookup.load(stores.typeOf(store.id).universes),
            ids,
            EveryItemFacets.siteOf(store),
            store.items.allFacetNames(),
            store.drafts.all(),
        )
    }

    /**
     * Whether the source drafts anything: it asks the catalogue for statements or has fandoms, and its type has
     * universes.
     */
    private fun drafts(store: SourceStore): Boolean =
        (store.source.catalogueProperties.isNotEmpty() || UniverseFacet.fandomFacet(store.schema) != null) &&
            stores.typeOf(store.id).type.universes

    /**
     * The drafts of the works [ids] made now of their site's values [facets], named by [names] (facet to key to name),
     * and kept where not what they were ([before]); the works whose drafts changed.
     */
    private fun redraft(
        store: SourceStore,
        lookup: DictionaryLookup,
        ids: List<String>,
        facets: Map<String, Map<String, List<String>>>,
        names: Map<String, Map<String, String>>,
        before: Map<String, List<LayerDraft>>,
    ): List<String> {
        val started = System.currentTimeMillis()
        val fandom = UniverseFacet.fandomFacet(store.schema)
        val made = HashMap<String, List<LayerDraft>>()
        ids.forEach { id ->
            val values =
                facets[id].orEmpty().mapValues { (facet, keys) -> keys.map { FacetValue(it, names[facet]?.get(it)) } }
            val drafted = if (lookup.empty) emptyList() else store.source.draft(values, lookup)
            made[id] = withUniverses(drafted + ofFandoms(store.id, fandom, values, lookup), lookup)
        }
        val changed = ids.filter { id -> made[id].orEmpty().toSet() != before[id].orEmpty().toSet() }
        if (changed.isNotEmpty()) store.drafts.replace(changed.associateWith { made[it].orEmpty() })
        if (changed.isNotEmpty() || ids.size > 1) {
            log.info(
                "{}: drafts of {} works made, {} changed, in {} ms",
                store.id,
                ids.size,
                changed.size,
                System.currentTimeMillis() - started,
            )
        }
        return changed
    }

    /**
     * The universes of a work's fandoms on its site ([values] of the [fandom] facet of [source]): the universe every
     * one is bound to and every universe above it, named by the fandom. None of a work its site says is of no universe
     * (realism, an alternative history, an original work: the fandom "originals").
     */
    private fun ofFandoms(
        source: String,
        fandom: String?,
        values: Map<String, List<FacetValue>>,
        lookup: DictionaryLookup,
    ): List<LayerDraft> {
        val kinds = values[UniverseFacets.KIND].orEmpty().map { it.key }
        val universeless = kinds.isNotEmpty() && kinds.all { it in UniverseFacets.UNIVERSELESS }
        val fandoms = if (fandom == null || universeless) emptyList() else values[fandom].orEmpty()
        return fandoms.flatMap { f ->
            lookup.universesOf(source, f.key).map { LayerDraft(UniverseFacets.UNIVERSE, it, f.name ?: f.key) }
        }
    }

    /** The drafts with the universe of every character drafted that the dictionary has in one universe alone. */
    private fun withUniverses(drafts: List<LayerDraft>, lookup: DictionaryLookup): List<LayerDraft> {
        val universes =
            drafts
                .filter { it.facet == UniverseFacets.CHARACTERS }
                .mapNotNull { d ->
                    lookup.characterUniverses[d.key]?.singleOrNull()?.let {
                        LayerDraft(UniverseFacets.UNIVERSE, it, d.from)
                    }
                }
        return (drafts + universes).distinct()
    }

    companion object {
        val DRAFTING = Task(TaskKind.DRAFT_WORK_LAYER)

        /**
         * The place of the drafting among the listeners of a change of works: after the sites' tags are bound
         * ([TagNormalization][io.github.vlsergey.recommend4me.tag.TagNormalization], -100), before whatever reads the
         * works.
         */
        private const val ORDER = -50

        /** The quiet after the last asking before a whole source is drafted again. */
        private const val QUIET_SECONDS = 2L
    }
}
