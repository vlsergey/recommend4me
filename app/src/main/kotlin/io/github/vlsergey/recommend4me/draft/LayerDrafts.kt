package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.DictionaryLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
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
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LayerDrafts::class.java)

/**
 * THE DRAFTS OF THE LAYER, MADE: of every work of a source that asks the catalogue for statements
 * ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]), what the source
 * drafts of it from its values on the site through the dictionary
 * ([Source.draft][io.github.vlsergey.recommend4me.source.Source.draft]) — and the universe of every character drafted
 * that is in one universe of the dictionary alone; and of every work of a source with fandoms, the universes the user
 * bound its fandoms to
 * ([FandomBindings.universesOf][io.github.vlsergey.recommend4me.universe.FandomBindings.universesOf]).
 *
 * Made again when the work is captured or refreshed — before anything reads it — and, of every work of the type, when
 * the dictionary changes and on start, of every work of a source when the user binds its fandoms anew ([fandomsBound])
 * — these in the background, in the queue of the computations ([Workload]), never on the thread of the start or of a
 * request: the works whose drafts changed are then read again as any changed work is.
 *
 * A draft is a value the work is given, marked so
 * ([Chance.draftedFrom][io.github.vlsergey.recommend4me.suggestion.Chance.draftedFrom]); the user's answers override
 * it, kept apart: a draft taken away stays taken away, whatever it is made of again.
 */
@Component
class LayerDrafts(
    private val stores: Stores,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {

    /** The drafts of whole sources made again: in the background, in the queue of the computations, after a quiet. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "layer-drafts").apply { isDaemon = true }
    }

    /** The sources to be drafted again whole; guarded by itself. */
    private val redrafted = LinkedHashSet<String>()

    /** Their drafting, put off by every asking till the askings are quiet; guarded by [redrafted]. */
    private var redrafting: ScheduledFuture<*>? = null

    /**
     * After the sites' tags are bound ([TagNormalization][io.github.vlsergey.recommend4me.tag.TagNormalization]),
     * before the model reads the works: the works written drafted at once — a few, before anything reads them. Every
     * work of the source — the dictionary changed — drafted in the background.
     */
    @Order(ORDER)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val store = stores.source(event.source)?.takeIf(::drafts) ?: return
        if (event.ids.isEmpty()) {
            redraftLater(store.id, "dictionary")
            return
        }
        val lookup = DictionaryLookup.load(stores.typeOf(store.id).universes)
        // Of many works one streamed pass, not a thousand at a time: each IN of a thousand is a scan in H2
        val facets = store.items.facetsOf(event.ids)
        // The names of the values as written, of every facet of the works at once
        val keys = HashMap<String, HashSet<String>>()
        facets.values.forEach { of -> of.forEach { (facet, values) -> keys.getOrPut(facet) { HashSet() } += values } }
        val names = keys.mapValues { (facet, values) -> store.items.facetNames(facet, values) }
        redraft(store, lookup, event.ids, facets, names, store.drafts.ofItems(event.ids))
    }

    /**
     * Every work drafted again after the start: the dictionary or the rules may have changed while the application was
     * down.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.filter(::drafts).forEach { redraftLater(it.id, "start") }
    }

    /**
     * The user bound a fandom of the [source] to a universe, or took it back: every work of the source drafted again.
     */
    fun fandomsBound(source: String) = redraftLater(source, "fandoms")

    /**
     * Every work of the [source] drafted again once the askings are quiet for [QUIET_SECONDS] — a few in a row draft
     * once — the works whose drafts changed then read again.
     */
    private fun redraftLater(source: String, reason: String) {
        synchronized(redrafted) {
            redrafted += source
            workload.plan(DRAFTING, Instant.now().plusSeconds(QUIET_SECONDS), reason)
            redrafting?.cancel(false)
            redrafting = worker.schedule(::redraftPlanned, QUIET_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Every work of the sources planned drafted again now, in the queue of the computations: what the background does
     * after the quiet.
     */
    @Suppress("TooGenericExceptionCaught")
    fun redraftPlanned() {
        val sources = synchronized(redrafted) { redrafted.toList().also { redrafted.clear() } }
        val changed =
            try {
                workload.run(DRAFTING) {
                    sources.mapNotNull { source ->
                        val store = stores.source(source)?.takeIf(::drafts) ?: return@mapNotNull null
                        try {
                            source to redraftWhole(store)
                        } catch (e: Exception) {
                            // A BOUNDARY: a source's drafting runs its plugin's code (Source.draft), which may fail
                            // in any way; the failure is logged and the other sources drafted
                            log.error("{}: the drafts of the works were not made", source, e)
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                // A BOUNDARY: this runs on the background thread, where nothing would hear of a failure of the queue
                // or of a work alike joined; it is logged
                log.error("The drafts of {} were not made", sources, e)
                return
            }
        // Told once out of the queue: whatever reads the works again plans its own work
        changed.forEach { (source, ids) -> if (ids.isNotEmpty()) events.publishEvent(ItemsChanged(source, ids)) }
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
        // The universes the user bound the source's fandoms to, read once for every work
        val fandom = UniverseFacet.fandomFacet(store.schema)
        val bound = if (fandom == null) emptyMap() else stores.typeOf(store.id).universes.fandoms.universesOf(store.id)
        val made = HashMap<String, List<LayerDraft>>()
        ids.forEach { id ->
            val values =
                facets[id].orEmpty().mapValues { (facet, keys) -> keys.map { FacetValue(it, names[facet]?.get(it)) } }
            val drafted = if (lookup.empty) emptyList() else store.source.draft(values, lookup)
            val ofFandoms =
                fandom
                    ?.let { values[it] }
                    .orEmpty()
                    .flatMap { f ->
                        bound[f.key].orEmpty().map { LayerDraft(UniverseFacets.UNIVERSE, it, f.name ?: f.key) }
                    }
            made[id] = withUniverses(drafted + ofFandoms, lookup)
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

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        val DRAFTING = Work("layerDrafts")

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
