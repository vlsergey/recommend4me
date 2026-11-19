package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FieldModelFitEvent
import io.github.vlsergey.recommend4me.suggestion.WorkModelValues
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkFields::class.java)

/**
 * The fields the models compute written of works of the [type], from where of each of the works ([from]): their
 * expected grade and interest made next ([WorkStage.PREDICTED]).
 */
data class WorkFieldFillEvent(val type: String, val from: Map<WorkScope, TaskCursor>)

/**
 * The fields of every marked and rated work of the [type] computed by the models as they are now: the models of the
 * grades made of them next (docs/computations.md, 1: the third stage).
 */
data class RatedWorkValueFillEvent(val type: String)

/**
 * THE FIELDS THE MODELS COMPUTE, WRITTEN INTO THE INDEX OF THE LIST — THE FIRST OF THE TASKS OVER THE WORKS (owner,
 * 2026-11-19: "the listener of the event of a change of the fields' models creates the task of computing the fields… in
 * two copies: for the marked and/or rated works and for the other works"; docs/computations.md, 9 and 10): of a source
 * whose fields' models were fitted anew ([fitted]), of an index made anew, every work's fields to be computed again
 * ([WorkStage.FIELDS]); a task of the marked and rated works, and one of the others after every other task, a [PORTION]
 * of them a turn ([turn]) — the highest expected first, from where the last turn left —: the values the models give
 * each made now ([WorkModelValues]), the documents of those other than kept made anew, every one marked done; told
 * ([WorkFieldFillEvent]): their expected grade and interest made next. The marked and rated all done, the models of the
 * grades are made of them ([RatedWorkValueFillEvent]).
 */
@Component
class WorkFields(
    private val stores: Stores,
    private val works: Works,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val modelValues: WorkModelValues,
    private val events: ApplicationEventPublisher,
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
     * The models of a source's fields fitted anew: every work of it done before is to be done again, the marked and
     * rated and the others from the first.
     */
    @EventListener
    fun fitted(event: FieldModelFitEvent) {
        val type = stores.typeOf(event.source)
        val index = indexes.of(type.id)
        index.stages.changed(since(event.source), System.currentTimeMillis())
        index.commit()
        WorkScope.entries.forEach { plan(type.id, it, "chances", TaskCursor.FIRST) }
    }

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
        val type = stores.type(typeId)
        val index = indexes.of(typeId)
        val items = type?.let { stale(it, index, scope, cursor, portion) }.orEmpty()
        if (type != null && items.isNotEmpty()) filled(type, index, scope, items)
        val next = items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
        // The pass of the marked and rated works ended — of this turn, or of the turns before when it found none left
        val passed = items.isNotEmpty() || cursor != TaskCursor.FIRST
        if (next == null && scope == WorkScope.MARKED && passed) events.publishEvent(RatedWorkValueFillEvent(typeId))
        return next
    }

    /**
     * The next [portion] items of the [scope] of the [type] whose fields are to be computed, from the [cursor] on: of
     * the sources whose models work out a field, those done before the models held.
     */
    private fun stale(
        type: TypeStore,
        index: ListIndex,
        scope: WorkScope,
        cursor: TaskCursor,
        portion: Int,
    ): List<TakenWork> {
        val since =
            type.sources
                .filter { s -> s.schema.facets.any { it.infer } }
                .associate { it.id to (index.stages.since(since(it.id)) ?: 1L) }
        return if (since.isEmpty()) emptyList()
        else NextWorks.next(index, scope, NextWorks.before(WorkStage.FIELDS, since), cursor, portion)
    }

    /** The fields of the [items] of the [scope] of the [type] computed, written where they changed, told. */
    private fun filled(type: TypeStore, index: ListIndex, scope: WorkScope, items: List<TakenWork>) {
        val started = System.currentTimeMillis()
        val clusters = works.of(type)
        val changed =
            items.groupBy({ it.key.source }, { it.key.id }).entries.sumOf { (source, ids) ->
                val s = type.source(source) ?: return@sumOf 0
                val known = EveryItemFacets(s.items.facetsOf(ids), s.corrections.facetsOf(ids))
                write(type, s, index, clusters, modelValues.of(s, ids, known), ids, started)
            }
        WorkStage.FIELDS.done(index, items.map { it.key }, started)
        index.commit()
        log.info(
            "{}: the fields of {} {} works computed in {} ms, {} of them other than kept",
            type.id,
            items.size,
            scope.value,
            System.currentTimeMillis() - started,
            changed,
        )
        events.publishEvent(WorkFieldFillEvent(type.id, mapOf(scope to items.first().cursor())))
    }

    /**
     * Of the items [ids] of the source [s], the documents of those whose values [fresh] are not those the [index] keeps
     * made anew — their fields computed [at], their expectation to be made again, their document of the search as it
     * was —: how many.
     */
    private fun write(
        type: TypeStore,
        s: SourceStore,
        index: ListIndex,
        clusters: WorkClusters,
        fresh: Map<String, Map<String, List<String>>>,
        ids: List<String>,
        at: Long,
    ): Int {
        val kept = ListModelValues.of(index, s.id, ids)
        val other = ids.filter { id -> asSets(fresh[id]) != asSets(kept[id]) }
        if (other.isNotEmpty()) {
            val stages = WorkStages.of(index, s.id, other)
            index.replace(
                ListRows.read(type, s, other, clusters, fresh) { WorkStages(at, 0, stages[it]?.textsAt ?: 0) }
            )
        }
        return other.size
    }

    companion object {
        /** The task of the queue computing the works' fields. */
        val KIND = TaskKind.FILL_WORK_FIELDS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** What the index tells of since when the models of the [source]'s fields hold ([StageTimes.since]). */
        fun since(source: String) = "fields:$source"

        /** The values of every facet as sets: their order is no change. */
        private fun asSets(values: Map<String, List<String>>?): Map<String, Set<String>> =
            values.orEmpty().mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    }
}
