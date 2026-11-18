package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetChancesChanged
import io.github.vlsergey.recommend4me.suggestion.TaskPortions
import io.github.vlsergey.recommend4me.suggestion.WorkModelValues
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ListFilling::class.java)

/**
 * THE WORKS' VALUES FILLED INTO THE INDEX OF THE LIST BY THE MODELS AS THEY ARE NOW (user, 2026-11-18: "split every
 * such work in two: making new models and filling the works with the new computed data"; "the setting of the new values
 * in Lucene lower in priority than the other actions"): a work of the queue of every source, LAST of it
 * ([Workload.plan] `last`), after the models of its facets are fitted anew or the index made anew. Every item of the
 * source, a portion at a time: the values the models give it made now ([WorkModelValues]), compared with those its
 * document keeps ([ListModelValues]), and of the items whose values changed alone the documents made anew — told to
 * what reads them ([FacetChancesChanged]: the index of the search, the training of the grades). How much is done told
 * to the queue.
 */
@Component
class ListFilling(
    private val stores: Stores,
    private val works: Works,
    private val workload: Workload,
    private val indexes: ListIndexes,
    private val modelValues: WorkModelValues,
    private val events: ApplicationEventPublisher,
    private val settings: Settings,
) {
    /**
     * What the last run of the application left undone of the filling, planned again; and of a source never filled
     * whole yet — its documents of the values the models gave mixed with the site's, none kept apart — a filling, once.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        workload.resume(WORK) { left, reason -> left.work.source?.let { fill(it, reason) } }
        stores.sources
            .filter { s -> s.schema.facets.any { it.infer } && settings.get(FILLED + s.id) == null }
            .forEach { fill(it.id, "start") }
    }

    /** The values of every work of the [source] filled anew, last of the queue, for the [reason] told. */
    fun fill(source: String, reason: String) =
        workload.plan(Work(WORK, source = source), Instant.now(), reason, last = true) { filled(source) }

    private fun filled(source: String) {
        val s = stores.source(source) ?: return
        val type = stores.typeOf(source)
        if (s.schema.facets.none { it.infer }) return
        val index = indexes.of(type.id)
        val clusters = works.of(type)
        val started = System.currentTimeMillis()
        val total = s.items.count().coerceAtLeast(1)
        var done = 0
        var changed = 0
        var after: String? = null
        do {
            val keys = s.items.keysAfter(after, TaskPortions.MOST)
            after = keys.lastOrNull()?.id
            val ids = keys.map { it.id }
            val known = EveryItemFacets(s.items.facetsOf(ids), s.corrections.facetsOf(ids))
            val fresh = modelValues.of(s, ids, known)
            val kept = ListModelValues.of(index, source, ids)
            val other = ids.filter { id -> asSets(fresh[id]) != asSets(kept[id]) }
            if (other.isNotEmpty()) {
                index.replace(ListRows.read(type, s, other, clusters, fresh))
                index.commit()
                events.publishEvent(FacetChancesChanged(source, other))
                changed += other.size
            }
            done += ids.size
            workload.progress(done.toDouble() / total)
        } while (keys.size == TaskPortions.MOST)
        settings.set(FILLED + source, Instant.now().toString())
        log.info(
            "{}: the values the models give {} works filled in {} ms, {} of them other than kept",
            source,
            done,
            System.currentTimeMillis() - started,
            changed,
        )
    }

    companion object {
        /** The work of the queue filling a source's values into the index. */
        const val WORK = "listValues"

        /** The setting of when a source was last filled whole, by the source. */
        private const val FILLED = "listValues.filled."

        /** The values of every facet as sets: their order is no change. */
        private fun asSets(values: Map<String, List<String>>?): Map<String, Set<String>> =
            values.orEmpty().mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    }
}
