package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.CastNamesEncoded
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(KeptModelValues::class.java)

/**
 * THE VALUES THE MODEL GIVES EVERY WORK, KEPT FOR THE FILTERS (user, 2026-11-17): a filter of the list on a facet the
 * model works out runs no model — of a million works the chain of the models over every work at every request was
 * minutes. Of every facet the source works out ([io.github.vlsergey.recommend4me.source.FacetDef.infer]) the values a
 * work is shown with beyond the site's own ([ModelValues.of]) — the drafts and the facets' models of their own among
 * them — written where they changed ([ModelValueRepository]):
 * - of every work by every pass of the models of the source's facets ([FacetRefits]), which makes the chances of every
 *   work anyway: what a model changed reaches every work;
 * - of the works a change names — their data, their texts' vectors, their casts' names, an answer on them — in a work
 *   of the queue of their own after a quiet, the chain made of them alone: a work new or changed is filtered by its
 *   values without waiting for the pass of every work.
 *
 * What the user answers is not among them: it is read with them, as of every facet. Till a work's values are written
 * the filters read what was written before.
 */
@Component
internal class KeptModelValues(
    private val stores: Stores,
    private val workload: Workload,
    private val chain: FacetChain,
    private val events: ApplicationEventPublisher,
) {
    /** The works of every source whose values are to be written anew, named by the changes since the last writing. */
    private val waiting = ConcurrentHashMap<String, MutableSet<String>>()

    @EventListener fun itemsChanged(event: ItemsChanged) = changed(event.source, event.ids, "items")

    @EventListener fun textVectorsChanged(event: TextVectorsChanged) = changed(event.source, event.ids, "texts")

    @EventListener fun castNamesEncoded(event: CastNamesEncoded) = changed(event.source, event.ids, "castNames")

    @EventListener fun answered(event: FacetAnswered) = changed(event.source, listOf(event.itemId), "answer")

    /**
     * The works [ids] of the [source] changed: their values written anew after the quiet, of every change of it at
     * once. No id names every work: the pass of every work, planned by the same change, writes them.
     */
    private fun changed(source: String, ids: List<String>, reason: String) {
        val store = stores.source(source) ?: return
        if (ids.isEmpty() || store.schema.facets.none { it.infer }) return
        waiting.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() }.addAll(ids)
        workload.plan(Work(WORK, source = source), Instant.now().plusSeconds(QUIET_SECONDS), reason) {
            writeWaiting(store)
        }
    }

    /** The values of the works waiting of the [store] written: the chain made of them alone. */
    private fun writeWaiting(store: SourceStore) {
        val ids = waiting.remove(store.id)?.toList().orEmpty()
        if (ids.isEmpty()) return
        val known = store.items.facetsOf(ids)
        write(store, ids, known, chain.chances(store, ids), every = false)
    }

    /** The values the [made] chances of every work of the pass [works] give, written where they changed. */
    fun keep(store: SourceStore, works: WorksRead, made: WorkChances) =
        write(store, works.ids, works.site, made, every = true)

    /**
     * The values the [made] chances of the works [ids] give beyond the site's own ([site]), written where they changed:
     * of [every] work of the source, the works of none of them among them.
     */
    private fun write(
        store: SourceStore,
        ids: Collection<String>,
        site: Map<String, Map<String, List<String>>>,
        made: WorkChances,
        every: Boolean,
    ) {
        val started = System.currentTimeMillis()
        val changed = HashSet<String>()
        store.schema.facets
            .filter { it.infer }
            .forEach { def ->
                val fresh = HashMap<String, List<String>>()
                ids.forEach { id ->
                    val chances = made[id]?.get(def.key) ?: return@forEach
                    val own = site[id]?.get(def.key).orEmpty()
                    val given =
                        ModelValues.of(store.schema, mapOf(def.key to own), mapOf(def.key to chances))[def.key]
                            .orEmpty()
                            .filter { it !in own }
                    if (given.isNotEmpty()) fresh[id] = given
                }
                val stored = store.modelValues.of(def.key, if (every) null else ids)
                val other =
                    (if (every) stored.keys + fresh.keys else ids)
                        .filter { id -> stored[id].orEmpty().toSet() != fresh[id].orEmpty().toSet() }
                        .associateWith { fresh[it].orEmpty() }
                store.modelValues.replace(def.key, other)
                changed += other.keys
            }
        if (changed.isNotEmpty()) events.publishEvent(ModelValuesChanged(store.id, changed.toList()))
        log.info(
            "{}: the values the model gives {} works kept, those of {} works written anew in {} ms",
            store.id,
            ids.size,
            changed.size,
            System.currentTimeMillis() - started,
        )
    }

    private companion object {
        /** The work of the queue writing the values of the works a change named. */
        const val WORK = "modelValues"

        /** The quiet after a change before the values of the works it named are written: a page sends itself again. */
        const val QUIET_SECONDS = 2L
    }
}
