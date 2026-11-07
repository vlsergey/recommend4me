package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.api.WorkloadApi
import io.github.vlsergey.recommend4me.api.model.QueuedWork
import io.github.vlsergey.recommend4me.api.model.WorkReason
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
class WorkloadController(private val workload: Workload, private val stores: Stores) : WorkloadApi {

    override fun listWorkload(): ResponseEntity<List<QueuedWork>> {
        val finished = workload.finished().map { it.toApi() }
        val running = listOfNotNull(workload.running()).map { it.toApi(QueuedWork.State.RUNNING) }
        val waiting = workload.waiting().map { it.toApi(QueuedWork.State.WAITING) }
        val planned = workload.planned().map { it.toApi() }
        return ResponseEntity.ok(finished + running + waiting + planned)
    }

    /**
     * The work as it stands: running, waiting, planned — a run to come before the last done —, else the last run of it
     * done; none of a work neither to come nor among the last done.
     */
    override fun getWork(
        what: String,
        type: String?,
        source: String?,
        facet: String?,
        item: String?,
    ): ResponseEntity<QueuedWork> {
        val work = Work(what, type, source, facet, item)
        val found =
            workload.running()?.takeIf { it.work == work }?.toApi(QueuedWork.State.RUNNING)
                ?: workload.waiting().firstOrNull { it.work == work }?.toApi(QueuedWork.State.WAITING)
                ?: workload.planned().firstOrNull { it.work == work }?.toApi()
                ?: workload.finished().lastOrNull { it.work == work }?.toApi()
        return found?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    private fun FinishedWork.toApi() =
        queued(work, QueuedWork.State.FINISHED, began, reasons)
            .copy(ended = ended.atOffset(ZoneOffset.UTC), failed = failed, givenUp = givenUp)

    private fun PlannedWork.toApi() =
        queued(work, QueuedWork.State.PLANNED, since, reasons).copy(due = due.atOffset(ZoneOffset.UTC))

    private fun HeavyWork.toApi(state: QueuedWork.State) =
        queued(work, state, since, reasons).copy(asked = asked.takeIf { it })

    /** A work as the page shows it; one not done with how long it is expected to take. */
    private fun queued(work: Work, state: QueuedWork.State, since: Instant, reasons: Map<String, Int>): QueuedWork {
        val source = work.source?.let(stores::source)
        val def = work.facet?.let { f -> source?.schema?.facets?.firstOrNull { it.key == f } }
        return QueuedWork(
            what = work.what,
            state = state,
            type = work.type ?: source?.type,
            source = work.source,
            facet = work.facet,
            labelKey = def?.let { I18nKeys.facetLabel(source!!.id, it) },
            item = work.item,
            since = since.atOffset(ZoneOffset.UTC),
            expectedMillis = if (state == QueuedWork.State.FINISHED) null else workload.expected(work),
            reasons = reasons.map { (key, count) -> WorkReason(key, count) },
        )
    }
}
