package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkValues::class.java)

/**
 * WHAT CHANGES OF A WORK WITHOUT ITS ITEMS, WRITTEN INTO THE INDEX OF THE LIST IN PLACE ([RowValues]): a grade, a mark
 * of interest, the predictions of a training or of the expectations — of the items named, their works' values, their
 * documents not made anew. A task of its own apart from the writing of the documents ([ListIndexing]): it leads to
 * nothing further — the expectations it writes are not a change of the works the tasks over them follow. What waits is
 * the items' names, kept in the queue ([TaskKeys.want]); the databases are read when the task runs.
 */
@Component
class WorkValues(private val stores: Stores, private val tasks: Tasks, private val indexes: ListIndexes) {
    // What the last run of the application left undone of it: planned again by the queue at its start — what it named
    // the queue kept
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /** The values of the items [keys] of the content type [typeId] to be written, for the [reason]. */
    fun change(typeId: String, reason: String, keys: Collection<ItemKey>) {
        tasks.keys.want(work(typeId), keys.map { "${it.source}$SEP${it.id}" })
        plan(typeId, reason)
    }

    /** The writing of the values waiting of the content type [typeId] planned, for the [reason]. */
    private fun plan(typeId: String, reason: String) =
        // A grade, a mark of interest: the user's own change, ahead of the background
        tasks.plan(
            work(typeId),
            Instant.now().plusSeconds(QUIET_SECONDS),
            reason,
            priority = TaskPriority.USER.takeIf { reason in USERS },
        ) {
            write(typeId)
        }

    /** The values waiting of the content type [typeId] written now, in the queue, for a caller that waits: a test. */
    fun writeWaiting(typeId: String) = tasks.run(work(typeId)) { write(typeId) }

    /** The values waiting of the content type [typeId] written to its index in place, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val taken = tasks.keys.wanted(work(typeId))
        if (taken.isEmpty()) return
        val started = System.currentTimeMillis()
        val index = indexes.of(typeId)
        taken.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) }).forEach { (sourceId, ids) ->
            val s = type.source(sourceId) ?: return@forEach
            val values = ListRows.valuesOf(type, s, ids)
            index.update(values.entries.associate { (id, v) -> ItemKey(sourceId, id) to ListDocuments.valueFields(v) })
        }
        index.commit()
        tasks.keys.done(work(typeId), taken)
        log.info(
            "{}: the values of {} items written in {} ms",
            typeId,
            taken.size,
            System.currentTimeMillis() - started,
        )
    }

    private companion object {
        /** The task of the queue writing the works' values in place. */
        val KIND = TaskKind.WRITE_WORK_VALUES

        /** An item's key as the queue keeps it: its source and its id. */
        const val SEP = '\t'

        /** The quiet after a change before the values are written: the changes of a moment written at once. */
        const val QUIET_SECONDS = 1L

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        val USERS = setOf("grade", "interest")

        fun work(typeId: String) = Task(KIND, type = typeId)
    }
}
