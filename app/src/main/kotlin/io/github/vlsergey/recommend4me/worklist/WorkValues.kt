package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkValues::class.java)

/**
 * THE PREDICTIONS OF THE WORKS, WRITTEN INTO THE INDEX OF THE LIST IN PLACE ([RowValues]): of a training, of the
 * expectations, of a work opened — of the items whose prediction changed as the index keeps it, to its digits
 * ([ListDocuments.kept]; owner, 2026-11-21: "the grade and the interest are three digits"), since the last writing of
 * them, by the dates the predictions keep ([io.github.vlsergey.recommend4me.model.PredictionRepository.changedSince];
 * the date of the writing kept with the index's commit, [WRITTEN]): their works' values, their documents not made anew.
 * A task of its own apart from the writing of the documents ([ListIndexing]), which writes the values of the documents
 * it makes — a grade, a mark of interest among them —: it leads to nothing further. Nothing is named in the queue.
 */
@Component
class WorkValues(private val stores: Stores, private val tasks: Tasks, private val indexes: ListIndexes) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /** The writing of the values changed of the content type [typeId] planned, for the [reason]. */
    fun plan(typeId: String, reason: String) =
        tasks.plan(work(typeId), Instant.now().plusSeconds(QUIET_SECONDS), reason) { write(typeId) }

    /** The values changed of the content type [typeId] written now, in the queue, for a caller that waits: a test. */
    fun writeWaiting(typeId: String) = tasks.run(work(typeId)) { write(typeId) }

    /** Whether a prediction of the [type] changed since its values were last written: what a start plans them of. */
    fun pending(type: TypeStore): Boolean = type.predictions.anySince(writtenOf(indexes.of(type.id)))

    /** The values changed of the content type [typeId] since their last writing written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val started = Instant.now()
        val index = indexes.of(typeId)
        val changed = type.predictions.changedSince(writtenOf(index))
        changed.forEach { (sourceId, ids) ->
            val s = type.source(sourceId) ?: return@forEach
            ids.chunked(PORTION).forEach { portion ->
                val values = ListRows.valuesOf(type, s, portion)
                index.update(
                    values.entries.associate { (id, v) -> ItemKey(sourceId, id) to ListDocuments.valueFields(v) }
                )
            }
        }
        index.stages.changed(WRITTEN, started.toEpochMilli())
        index.commit()
        log.info(
            "{}: the values of {} items written in {} ms",
            typeId,
            changed.values.sumOf { it.size },
            System.currentTimeMillis() - started.toEpochMilli(),
        )
    }

    private companion object {
        /** The task of the queue writing the works' values in place. */
        val KIND = TaskKind.WRITE_WORK_VALUES

        /**
         * Of the stages the index keeps with its commits ([StageTimes]): since when the values of its documents hold
         * every prediction — the start of their last writing, milliseconds.
         */
        const val WRITTEN = "valuesWritten"

        /** The items whose values are read and written at a time: the memory of a writing of many, not a meaning. */
        const val PORTION = 10_000

        /** Since when the values of the [index] hold every prediction; of an index never so written, of every date. */
        fun writtenOf(index: ListIndex): Instant =
            index.stages.since(WRITTEN)?.let(Instant::ofEpochMilli) ?: Instant.EPOCH

        /** The quiet after a change before the values are written: the changes of a moment written at once. */
        const val QUIET_SECONDS = 1L

        fun work(typeId: String) = Task(KIND, type = typeId)
    }
}
