package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.database.AppDatabase
import io.github.vlsergey.recommend4me.database.app.tables.references.QUEUED_KEY
import io.github.vlsergey.recommend4me.database.app.tables.references.QUEUED_TASK
import io.github.vlsergey.recommend4me.database.app.tables.references.TASK_RUN
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(QueuedTasks::class.java)

/**
 * THE QUEUE OF THE COMPUTATIONS IN THE APPLICATION'S DATABASE ([TaskRecords]): a row of every work not done yet, and of
 * every kind of work its last [KEPT_RUNS] runs. The rows of the last run of the application are told apart from this
 * one's by their plans' numbers, below [firstPlan]: taken by their owners at the start ([left]), the rest told in the
 * log and dropped once every owner took its own ([forget]).
 */
@Component
class QueuedTasks(app: AppDatabase) : TaskRecords {
    private val db = app.dsl

    override val firstPlan: Long = System.currentTimeMillis() * PLANS_A_MILLI

    override fun planned(
        work: Task,
        planNo: Long,
        due: Instant,
        since: Instant,
        reasons: Map<String, Int>,
        asked: Boolean,
    ) {
        db.insertInto(QUEUED_TASK)
            .set(QUEUED_TASK.TASK_KEY, key(work))
            .set(QUEUED_TASK.WHAT, work.what.value)
            .set(QUEUED_TASK.TYPE_ID, work.type)
            .set(QUEUED_TASK.SOURCE_ID, work.source)
            .set(QUEUED_TASK.FACET, work.facet)
            .set(QUEUED_TASK.ITEM, work.item)
            .set(QUEUED_TASK.PLAN_NO, planNo)
            .set(QUEUED_TASK.DUE, due)
            .set(QUEUED_TASK.SINCE, since)
            .set(QUEUED_TASK.REASONS, told(reasons))
            .set(QUEUED_TASK.ASKED, asked)
            .onDuplicateKeyUpdate()
            .set(QUEUED_TASK.PLAN_NO, planNo)
            .set(QUEUED_TASK.DUE, due)
            .set(QUEUED_TASK.SINCE, since)
            .set(QUEUED_TASK.REASONS, told(reasons))
            .set(QUEUED_TASK.ASKED, asked)
            .execute()
    }

    override fun ended(work: Task, planNo: Long) {
        db.deleteFrom(QUEUED_TASK).where(QUEUED_TASK.TASK_KEY.eq(key(work)), QUEUED_TASK.PLAN_NO.eq(planNo)).execute()
    }

    override fun ran(run: FinishedTask) {
        val kind = key(run.work.copy(item = null))
        db.insertInto(TASK_RUN)
            .set(TASK_RUN.KIND_KEY, kind)
            .set(TASK_RUN.WHAT, run.work.what.value)
            .set(TASK_RUN.TYPE_ID, run.work.type)
            .set(TASK_RUN.SOURCE_ID, run.work.source)
            .set(TASK_RUN.FACET, run.work.facet)
            .set(TASK_RUN.ITEM, run.work.item)
            .set(TASK_RUN.BEGAN, run.began)
            .set(TASK_RUN.ENDED, run.ended)
            .set(TASK_RUN.REASONS, told(run.reasons))
            .set(TASK_RUN.FAILED, run.failed)
            .set(TASK_RUN.GIVEN_UP, run.givenUp)
            .execute()
        // Of the kind, its last runs alone
        val kept =
            db.select(TASK_RUN.ID)
                .from(TASK_RUN)
                .where(TASK_RUN.KIND_KEY.eq(kind))
                .orderBy(TASK_RUN.ENDED.desc(), TASK_RUN.ID.desc())
                .limit(KEPT_RUNS)
                .fetch(TASK_RUN.ID)
        db.deleteFrom(TASK_RUN).where(TASK_RUN.KIND_KEY.eq(kind), TASK_RUN.ID.notIn(kept)).execute()
    }

    override fun runs(): List<FinishedTask> =
        db.selectFrom(TASK_RUN)
            .orderBy(TASK_RUN.ENDED.desc(), TASK_RUN.ID.desc())
            .limit(TOLD_RUNS)
            .fetch()
            // A run of a kind no longer done is not told
            .mapNotNull { r ->
                val what = kindOf(r.what!!) ?: return@mapNotNull null
                FinishedTask(
                    Task(what, r.typeId, r.sourceId, r.facet, r.item),
                    r.began!!,
                    r.ended!!,
                    parsed(r.reasons!!),
                    r.failed!!,
                    r.givenUp!!,
                )
            }
            .reversed()

    override fun expected(work: Task): Long? {
        val last =
            db.select(TASK_RUN.BEGAN, TASK_RUN.ENDED)
                .from(TASK_RUN)
                .where(TASK_RUN.KIND_KEY.eq(key(work.copy(item = null))), TASK_RUN.GIVEN_UP.isFalse)
                .orderBy(TASK_RUN.ENDED.desc(), TASK_RUN.ID.desc())
                .limit(MEAN_OF)
                .fetch()
        if (last.isEmpty()) return null
        return last.sumOf { Duration.between(it.value1()!!, it.value2()!!).toMillis() } / last.size
    }

    override fun left(what: TaskKind): List<LeftTask> {
        val rows =
            db.selectFrom(QUEUED_TASK).where(QUEUED_TASK.WHAT.eq(what.value), QUEUED_TASK.PLAN_NO.lt(firstPlan)).fetch()
        if (rows.isEmpty()) return emptyList()
        db.deleteFrom(QUEUED_TASK)
            .where(QUEUED_TASK.TASK_KEY.`in`(rows.map { it.taskKey }), QUEUED_TASK.PLAN_NO.lt(firstPlan))
            .execute()
        return rows.map { r ->
            LeftTask(Task(what, r.typeId, r.sourceId, r.facet, r.item), parsed(r.reasons!!), r.asked!!)
        }
    }

    override fun want(work: Task, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val taskKey = key(work)
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.batch(
                    chunk.map { k ->
                        db.mergeInto(QUEUED_KEY)
                            .using(db.selectOne())
                            .on(QUEUED_KEY.TASK_KEY.eq(taskKey), QUEUED_KEY.ITEM_KEY.eq(k))
                            .whenNotMatchedThenInsert(QUEUED_KEY.TASK_KEY, QUEUED_KEY.ITEM_KEY)
                            .values(taskKey, k)
                    }
                )
                .execute()
        }
    }

    override fun wanted(work: Task): List<String> =
        db.select(QUEUED_KEY.ITEM_KEY)
            .from(QUEUED_KEY)
            .where(QUEUED_KEY.TASK_KEY.eq(key(work)))
            .fetch(QUEUED_KEY.ITEM_KEY)
            .filterNotNull()

    override fun done(work: Task, keys: Collection<String>) {
        val taskKey = key(work)
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.deleteFrom(QUEUED_KEY).where(QUEUED_KEY.TASK_KEY.eq(taskKey), QUEUED_KEY.ITEM_KEY.`in`(chunk)).execute()
        }
    }

    /** Once every owner took its works at the start: those of the last run nobody took, told and dropped. */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent::class)
    fun forget() {
        val rest =
            db.select(QUEUED_TASK.TASK_KEY)
                .from(QUEUED_TASK)
                .where(QUEUED_TASK.PLAN_NO.lt(firstPlan))
                .fetch(QUEUED_TASK.TASK_KEY)
        if (rest.isNotEmpty()) {
            log.warn("works the last run left that nobody does again: {}", rest.map { it?.replace('\t', '/') })
            db.deleteFrom(QUEUED_TASK).where(QUEUED_TASK.PLAN_NO.lt(firstPlan)).execute()
        }
        // The keys of no work in the queue: of a work nobody does again, or of one that failed and is not planned
        val orphans =
            db.deleteFrom(QUEUED_KEY)
                .whereNotExists(db.selectOne().from(QUEUED_TASK).where(QUEUED_TASK.TASK_KEY.eq(QUEUED_KEY.TASK_KEY)))
                .execute()
        if (orphans > 0) log.info("{} keys of works no longer in the queue dropped", orphans)
    }

    private companion object {
        /** The runs kept of every kind of work, and those whose mean a work to come is expected to take. */
        const val KEPT_RUNS = 5
        const val MEAN_OF = 3

        /** The last runs the interface is told of. */
        const val TOLD_RUNS = 5

        /** The keys written or removed by one statement: the length of its list, not a meaning. */
        const val BATCH = 1000

        /** The plans of a millisecond a run of the application may make before its numbers reach the next one's. */
        const val PLANS_A_MILLI = 1000L

        fun key(work: Task): String =
            listOf(work.what.value, work.type, work.source, work.facet, work.item).joinToString("\t") { it.orEmpty() }

        /** The kind of a work kept by its value; none of a kind no longer done. */
        fun kindOf(value: String): TaskKind? = TaskKind.entries.firstOrNull { it.value == value }

        fun told(reasons: Map<String, Int>): String = reasons.entries.joinToString(",") { "${it.key}=${it.value}" }

        fun parsed(text: String): Map<String, Int> =
            text
                .split(',')
                .filter { it.isNotEmpty() }
                .associate {
                    it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 1)
                }
    }
}
