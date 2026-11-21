package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.TaskCursor
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.search.Query
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * THE SWEEPS OF THE STAGES OF THE TASKS OVER THE WORKS (owner, 2026-11-21: "every consumer has a variable of time — up
 * to which date every element is processed"): of every stage, scope and source the date up to which it did every change
 * it reads ([StageMarks.mark]), kept with the commits of the index of the list. A SWEEP of a stage takes the documents
 * of the scope with a date of what it reads past its mark and up to its horizon — the earlier of the index's writing
 * and the marks of the stages it waits for ([WorkStage.after]) —, the highest expected first, a portion a turn ([next],
 * [StageDue]); a document done is marked by the sweep ([done]), not taken again by it; what it made other than it was
 * dated by the horizon ([made]): the stages reading it see it. The documents all done, the mark is the horizon
 * ([complete]): the stage tells the stages after it, each by an event of its own. A stage to be done of every work
 * again — its models fitted anew, an encoder of another — is marked at nought ([reset]): every document of its sources
 * is taken. No date compares a document with another: a range of dates the stage reads, and a mark.
 */
@Component
class StageSweeps(
    private val stores: Stores,
    private val lists: ListIndexes,
) {

    /**
     * Of every stage, scope and source of no mark yet — the stages did their work before they kept marks — the mark as
     * of the index's writing now, kept: what changes after it is theirs to do. Before anything writes the index.
     */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.types.forEach { type ->
            val index = lists.of(type.id)
            val unmarked =
                WorkStage.entries
                    .flatMap { stage ->
                        WorkScope.entries.flatMap { scope ->
                            type.sources.map { StageMarks.markKey(stage, scope, it.id) }
                        }
                    }
                    .filter { index.stages.since(it) == null }
            if (unmarked.isEmpty()) return@forEach
            unmarked.forEach { index.stages.changed(it, StageMarks.written(index)) }
            index.commit()
        }

    /**
     * The next [portion] works of the [scope] of the [typeId] the [stage] is to do, of the [sources], from the [cursor]
     * on, the highest expected first: of the sweep under way, or of one begun now. [pending]: of a stage whose own
     * store tells exactly what waits for it (the parts not encoded), those works, not the dates of what it reads.
     */
    fun next(
        stage: WorkStage,
        typeId: String,
        sources: Collection<String>,
        scope: WorkScope,
        cursor: TaskCursor,
        portion: Int,
        pending: Query? = null,
    ): List<TakenWork> {
        val index = lists.of(typeId)
        val began = StageMarks.began(index, stage, scope) ?: begin(index, stage, scope, sources)
        val due =
            pending?.let { StageDue.pending(it, stage, began) } ?: StageDue.of(index, stage, scope, sources, began)
        return NextWorks.next(index, scope, due, cursor, portion)
    }

    /** The [keys] done of the [stage] by its sweep of the [scope] under way, in place: not taken by it again. */
    fun done(stage: WorkStage, typeId: String, scope: WorkScope, keys: Collection<ItemKey>) {
        val index = lists.of(typeId)
        StageMarks.began(index, stage, scope)?.let { stage.done(index, keys, it) }
    }

    /**
     * What the [stage] made of the [keys] came out other than it was: dated, in place, by the horizon of its sweep of
     * the [scope] — the stages reading it take them as they get past it.
     */
    fun made(stage: WorkStage, typeId: String, scope: WorkScope, keys: Collection<ItemKey>) {
        val output = stage.output ?: return
        val index = lists.of(typeId)
        index.update(
            keys.associateWith { arrayOf(NumericDocValuesField(output.field, dateOf(stage, typeId, scope, it))) }
        )
    }

    /** The date what the [stage] makes of the item [key] in its sweep of the [scope] is given: the sweep's horizon. */
    fun dateOf(stage: WorkStage, typeId: String, scope: WorkScope, key: ItemKey): Long {
        val index = lists.of(typeId)
        return StageMarks.horizon(index, stage, scope, key.source) ?: StageMarks.written(index)
    }

    /**
     * The sweep of the [stage] of the [scope] of the [typeId] done: every source marked up to its horizon, committed.
     * Nothing of a sweep that moved no mark. Whether it moved one: the stages after it to be told.
     */
    fun complete(stage: WorkStage, typeId: String, scope: WorkScope): Boolean {
        val index = lists.of(typeId)
        StageMarks.began(index, stage, scope) ?: return false
        val horizons = StageMarks.horizons(index, stage, scope)
        val moved = horizons.filter { (source, horizon) ->
            val before = StageMarks.mark(index, stage, scope, source)
            index.stages.changed(StageMarks.markKey(stage, scope, source), horizon)
            horizon != before
        }
        StageMarks.forgetSweep(index, stage, scope)
        // Of a sweep that moved no mark, nothing kept: the next commit forgets it
        if (moved.isNotEmpty()) index.commit()
        return moved.isNotEmpty()
    }

    /**
     * The [stage] to be done of every work of the [sources] of the [typeId] again — its models fitted anew, an encoder
     * of another —: marked at nought of every scope, a sweep under way given up; committed.
     */
    fun reset(stage: WorkStage, typeId: String, sources: Collection<String>) {
        val index = lists.of(typeId)
        WorkScope.entries.forEach { scope ->
            sources.forEach { index.stages.changed(StageMarks.markKey(stage, scope, it), 0L) }
            StageMarks.forgetSweep(index, stage, scope)
        }
        index.commit()
    }

    /** A sweep of the [stage] of the [scope] begun now: of every source its horizon kept with it. */
    private fun begin(index: ListIndex, stage: WorkStage, scope: WorkScope, sources: Collection<String>): Long {
        val began = System.currentTimeMillis()
        sources.forEach { StageMarks.begin(index, stage, scope, it, horizon(index, stage, scope, it)) }
        index.stages.changed(StageMarks.sweepKey(stage, scope), began)
        return began
    }

    /**
     * Up to when the [stage] may do the changes of the [source] now: the earlier of the index's writing — what the
     * documents hold of the databases — and the marks of the stages it waits for.
     */
    private fun horizon(index: ListIndex, stage: WorkStage, scope: WorkScope, source: String): Long =
        (listOf(StageMarks.written(index)) + stage.after.map { StageMarks.mark(index, it, scope, source) }).min()

    private companion object {
        /** Among the listeners of the start: before the index of the list or any stage is planned. */
        const val START_ORDER = -100
    }
}
