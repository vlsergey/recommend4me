package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.TasksApi
import io.github.vlsergey.recommend4me.api.model.QueuedTask
import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskReason
import io.github.vlsergey.recommend4me.api.model.TaskReasonKey
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * THE QUEUE OF THE BACKGROUND WORKS, for the page of the models: what runs, what waits — in the order it will run, the
 * works the user asked for first — what is planned, and why; and one work as it stands, for a screen waiting on it.
 */
@RestController
class TasksController(
    private val tasks: Tasks,
    private val records: TaskRecords,
    private val stores: Stores,
) : TasksApi {

    override fun listTasks(): ResponseEntity<List<QueuedTask>> {
        val finished = records.runs().map { it.toApi() }
        val running = listOfNotNull(tasks.running()).map { it.toApi(QueuedTask.State.RUNNING) }
        val waiting = tasks.waiting().map { it.toApi(QueuedTask.State.WAITING) }
        val planned = tasks.planned().map { it.toApi() }
        return ResponseEntity.ok(finished + running + waiting + planned)
    }

    /**
     * The work as it stands: running, waiting, planned — a run to come before the last done —, else the last run of it
     * done; none of a work neither to come nor among the last done.
     */
    override fun getTask(
        what: TaskKind,
        type: String?,
        source: String?,
        facet: String?,
        item: String?,
    ): ResponseEntity<QueuedTask> {
        val work = Task(what, type, source, facet, item)
        val found =
            tasks.running()?.takeIf { it.work == work }?.toApi(QueuedTask.State.RUNNING)
                ?: tasks.waiting().firstOrNull { it.work == work }?.toApi(QueuedTask.State.WAITING)
                ?: tasks.planned().firstOrNull { it.work == work }?.toApi()
                ?: records.runs().lastOrNull { it.work == work }?.toApi()
        return found?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    private fun FinishedTask.toApi() =
        queued(work, QueuedTask.State.FINISHED, began, reasons)
            .copy(ended = ended.atOffset(ZoneOffset.UTC), failed = failed, givenUp = givenUp)

    private fun PlannedTask.toApi() =
        queued(work, QueuedTask.State.PLANNED, since, reasons)
            .copy(
                due = due.atOffset(ZoneOffset.UTC),
                priority = priority,
                cursorGrade = cursor?.grade,
                cursorWork = cursor?.work,
            )

    private fun RunningTask.toApi(state: QueuedTask.State) =
        queued(work, state, since, reasons)
            .copy(
                priority = priority,
                cursorGrade = cursor?.grade,
                cursorWork = cursor?.work,
                progress = progress,
                step = step?.first,
                stepOf = step?.second,
            )

    /** A work as the page shows it; one not done with how long it is expected to take. */
    private fun queued(work: Task, state: QueuedTask.State, since: Instant, reasons: Map<String, Int>): QueuedTask {
        val source = work.source?.let(stores::source)
        val def = work.facet?.let { f -> source?.schema?.facets?.firstOrNull { it.key == f } }
        return QueuedTask(
            what = work.what,
            state = state,
            type = work.type ?: source?.type,
            source = work.source,
            facet = work.facet,
            labelKey = def?.let { I18nKeys.facetLabel(source!!.id, it) },
            item = work.item,
            works = work.works,
            since = since.atOffset(ZoneOffset.UTC),
            expectedMillis = if (state == QueuedTask.State.FINISHED) null else records.expected(work),
            reasons = reasons.map { (key, count) -> TaskReason(TaskReasonKey.forValue(key), count) },
        )
    }
}
