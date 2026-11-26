package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.cast.WorkCastNameFillEvent
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.setvector.WorkSetVectorFillEvent
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FieldModelFitEvent
import io.github.vlsergey.recommend4me.suggestion.WorkModelValues
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.WorkTextVectorFillEvent
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkFields::class.java)

/**
 * Every change the fields read of the works of the [scope] of the [type] done ([WorkStage.FIELDS]): the stages reading
 * them, or waiting for them, look again.
 */
data class WorkFieldFillEvent(val type: String, val scope: WorkScope)

/**
 * The fields of every marked and rated work of the [type] computed by the models as they are now: the models of the
 * grades made of them next (docs/computations.md, 1: the third stage).
 */
data class RatedWorkValueFillEvent(val type: String)

/**
 * THE FIELDS THE MODELS COMPUTE, WRITTEN INTO THE INDEX OF THE LIST — THE STAGE [WorkStage.FIELDS] ([StageSweeps];
 * owner, 2026-11-19: "the listener of the event of a change of the fields' models creates the task of computing the
 * fields… in two copies: for the marked and/or rated works and for the other works"; docs/computations.md, 9 and 10):
 * of the works whose data, answers, vectors, pictures, sets or cast changed past its mark — the stages making them done
 * of them first —, of every work of a source whose fields' models were fitted anew ([fitted]); a task of the marked and
 * rated works, and one of the others after every other task, a [PORTION] of them a turn ([turn]) — the highest expected
 * first, from where the last turn left —: the values the models give each made now ([WorkModelValues]), the documents
 * of those other than kept made anew and dated so, every one marked done. The marked and rated all done, the models of
 * the grades are made of them ([RatedWorkValueFillEvent]).
 */
@Component
class WorkFields(
    private val stores: Stores,
    private val works: Works,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val modelValues: WorkModelValues,
    private val sweeps: StageSweeps,
    private val events: ApplicationEventPublisher,
    private val plugins: Plugins,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scope = left.work.works ?: return@resumes
            plan(type, scope, reason, left.cursor ?: TaskCursor.FIRST)
        }
    }

    /**
     * The models of a source's fields fitted anew: every work of it is to be done again, the marked and rated and the
     * others from the first.
     */
    @EventListener
    fun fitted(event: FieldModelFitEvent) {
        val type = stores.typeOf(event.source)
        sweeps.reset(STAGE, type.id, listOf(event.source))
        WorkScope.entries.forEach { plan(type.id, it, "chances", TaskCursor.FIRST) }
    }

    /** The documents of works written anew, of what the fields are made of changed: their fields computed. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages)
            WorkScope.entries.forEach {
                plan(event.type, it, "items", TaskCursor.FIRST)
            }
    }

    /** The vectors of the texts the fields wait for done: the fields of the works they got past computed. */
    @EventListener
    fun vectorsFilled(event: WorkTextVectorFillEvent) = plan(event.type, event.scope, "texts", TaskCursor.FIRST)

    /** The names of the casts the fields wait for done: the fields of the works they got past computed. */
    @EventListener
    fun castNamesFilled(event: WorkCastNameFillEvent) = plan(event.type, event.scope, "castNames", TaskCursor.FIRST)

    /** The sets the fields wait for done: the fields of the works they got past computed. */
    @EventListener
    fun setsFilled(event: WorkSetVectorFillEvent) = plan(event.type, event.scope, "setVectors", TaskCursor.FIRST)

    /** The fields of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(KIND, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose fields are to be computed, from the
     * [cursor] on, done; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId) ?: return null
        // Of the sources whose models work out a field
        val sources = type.sources.filter { s -> s.schema.facets.any { it.infer } }.map { it.id }
        val items = sweeps.next(STAGE, typeId, sources, scope, cursor, portion)
        if (items.isNotEmpty()) filled(type, indexes.of(typeId), scope, items)
        val next = items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
        // The marked and rated all done: the models of the grades made of them
        if (next == null && sweeps.complete(STAGE, typeId, scope)) {
            events.publishEvent(WorkFieldFillEvent(typeId, scope))
            if (scope == WorkScope.MARKED) events.publishEvent(RatedWorkValueFillEvent(typeId))
        }
        return next
    }

    /** The fields of the [items] of the [scope] of the [type] computed, written where they changed, told. */
    private fun filled(type: TypeStore, index: ListIndex, scope: WorkScope, items: List<TakenWork>) {
        val started = System.currentTimeMillis()
        val clusters = works.of(type)
        val changed =
            items.groupBy({ it.key.source }, { it.key.id }).entries.sumOf { (source, ids) ->
                val s = type.source(source) ?: return@sumOf 0
                val known = EveryItemFacets(s.items.facetsOf(ids), s.corrections.facetsOf(ids))
                write(s, index, scope, clusters, modelValues.of(s, ids, known), ids)
            }
        sweeps.done(STAGE, type.id, scope, items.map { it.key })
        index.commit()
        log.info(
            "{}: the fields of {} {} works computed in {} ms, {} of them other than kept",
            type.id,
            items.size,
            scope.value,
            System.currentTimeMillis() - started,
            changed,
        )
    }

    /**
     * Of the items [ids] of the source [s], the documents of those whose values [fresh] are not those the [index] keeps
     * made anew, with their stages and dates as written, what the models gave them dated by the stage's horizon
     * ([StageSweeps.dateOf]): the expectation and whatever reads the fields see them. How many.
     */
    private fun write(
        s: SourceStore,
        index: ListIndex,
        scope: WorkScope,
        clusters: WorkClusters,
        fresh: Map<Long, Map<String, List<String>>>,
        ids: List<Long>,
    ): Int {
        val kept = index.written { ListModelValues.of(it, s.id, ids) }
        val other = ids.filter { id -> asSets(fresh[id]) != asSets(kept[id]) }
        if (other.isNotEmpty()) {
            val (predicted, written) =
                index.written { ListPredictions.of(it, s.id, other) to ListKeptRows.of(it, s.id, other) }
            index.replace(
                ListRows.read(s, other, clusters, fresh, predicted, plugins.textEncoder()?.id).map { r ->
                    r.over(written[r.key.id]).dated(WorkDatum.FIELDS, sweeps.dateOf(STAGE, s.type, scope, r.key))
                }
            )
        }
        return other.size
    }

    companion object {
        /** The task of the queue computing the works' fields. */
        val KIND = TaskKind.FILL_WORK_FIELDS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.FIELDS

        /** The values of every facet as sets: their order is no change. */
        private fun asSets(values: Map<String, List<String>>?): Map<String, Set<String>> =
            values.orEmpty().mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    }
}
