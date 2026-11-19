package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.TaskPortions
import io.github.vlsergey.recommend4me.suggestion.WorkModelValues
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ListFilling::class.java)

/** The values of the rated works of a [source] of the [type] filled into the list index: the third stage next. */
data class RatedWorkValueFillEvent(val type: String, val source: String)

/**
 * THE VALUES THE MODELS GIVE THE WORKS, FILLED INTO THE INDEX OF THE LIST (user, 2026-11-18: "split every such work in
 * two: making new models and filling the works with the new computed data"; owner, 2026-11-19: the four stages,
 * docs/computations.md).
 *
 * THE SECOND STAGE ([fill]): after the models of a source's facets are fitted anew, of every work of the source the
 * user graded or marked of interest the values the models give it made now ([WorkModelValues]), compared with those its
 * document keeps ([ListModelValues]), the documents of those that changed made anew; told after
 * ([RatedWorkValueFillEvent]): the models of the grades are made of these values next. A few works — the user's — not
 * every one: the works not rated are filled by the fourth stage, a thousand at a time ([written]).
 */
@Component
class ListFilling(
    private val stores: Stores,
    private val works: Works,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val modelValues: WorkModelValues,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        // Of a work a source of before, that source
        tasks.resumption.resumes(WORK) { left, reason ->
            left.work.source?.let { fill(it, reason) } ?: left.work.type?.let { plan(it, reason) }
        }
    }

    /**
     * The values of the rated works of the [source] filled anew, for the [reason] the interface tells: the source kept
     * waiting in the queue ([TaskKeys.want]) — ONE WORK OF THE TYPE, of every source of it waiting, not a work a
     * source.
     */
    fun fill(source: String, reason: String, last: Boolean = false) {
        val type = stores.typeOf(source).id
        tasks.keys.want(filling(type), listOf(source))
        plan(type, reason, last)
    }

    /**
     * The filling of the sources of the [type] waiting planned, for the [reason]; [last]: after the background's works.
     */
    private fun plan(type: String, reason: String, last: Boolean = false) =
        tasks.plan(filling(type), Instant.now(), reason, last = last) {
            val taken = tasks.keys.wanted(filling(type))
            taken.forEach(::filled)
            tasks.keys.done(filling(type), taken)
        }

    private fun filling(type: String) = Task(WORK, type = type)

    private fun filled(source: String) {
        val s = stores.source(source) ?: return
        val type = stores.typeOf(source)
        if (s.schema.facets.any { it.infer }) {
            val started = System.currentTimeMillis()
            val rated = (s.ratings.all().map { it.itemId } + s.interest.all().keys).distinct()
            val index = indexes.of(type.id)
            val clusters = works.of(type)
            var changed = 0
            rated.chunked(TaskPortions.MOST).forEachIndexed { k, ids ->
                val known = EveryItemFacets(s.items.facetsOf(ids), s.corrections.facetsOf(ids))
                changed += write(type, s, index, clusters, modelValues.of(s, ids, known), ids)
                tasks.progress(minOf(1.0, (k + 1.0) * TaskPortions.MOST / rated.size.coerceAtLeast(1)))
            }
            log.info(
                "{}: the values the models give {} rated works filled in {} ms, {} of them other than kept",
                source,
                rated.size,
                System.currentTimeMillis() - started,
                changed,
            )
        }
        events.publishEvent(RatedWorkValueFillEvent(type.id, source))
    }

    /**
     * The values [given] — of every item, those the models gave it beyond its site's own, made by another reading (the
     * fourth stage's) — written into the index of the [type] where they are not those kept: how many documents were
     * made anew.
     */
    fun written(type: TypeStore, given: Map<ItemKey, Map<String, List<String>>>): Int {
        val index = indexes.of(type.id)
        val clusters = works.of(type)
        return given.entries.groupBy({ it.key.source }, { it.key.id to it.value }).entries.sumOf { (source, values) ->
            type.source(source)?.let { s ->
                write(type, s, index, clusters, values.toMap(), values.map { it.first })
            } ?: 0
        }
    }

    /**
     * Of the items [ids] of the source [s], the documents of those whose values [fresh] are not those the [index] keeps
     * made anew and committed: how many.
     */
    private fun write(
        type: TypeStore,
        s: SourceStore,
        index: ListIndex,
        clusters: WorkClusters,
        fresh: Map<String, Map<String, List<String>>>,
        ids: List<String>,
    ): Int {
        val kept = ListModelValues.of(index, s.id, ids)
        val other = ids.filter { id -> asSets(fresh[id]) != asSets(kept[id]) }
        if (other.isNotEmpty()) {
            index.replace(ListRows.read(type, s, other, clusters, fresh))
            index.commit()
        }
        return other.size
    }

    companion object {
        /** The work of the queue filling the values of a source's rated works into the index. */
        val WORK = TaskKind.LIST_VALUES

        /** The values of every facet as sets: their order is no change. */
        private fun asSets(values: Map<String, List<String>>?): Map<String, Set<String>> =
            values.orEmpty().mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    }
}
