package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.database.AppDatabase
import io.github.vlsergey.recommend4me.database.app.tables.references.QUEUED_WORK
import io.github.vlsergey.recommend4me.database.app.tables.references.WORK_RUN
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(QueuedWorks::class.java)

/**
 * THE QUEUE OF THE COMPUTATIONS IN THE APPLICATION'S DATABASE ([WorkRecords]): a row of every work not done yet, and of
 * every kind of work its last [KEPT_RUNS] runs. The rows of the last run of the application are told apart from this
 * one's by their plans' numbers, below [firstPlan]: taken by their owners at the start ([left]), the rest told in the
 * log and dropped once every owner took its own ([forget]).
 */
@Component
class QueuedWorks(app: AppDatabase) : WorkRecords {
    private val db = app.dsl

    override val firstPlan: Long = System.currentTimeMillis() * PLANS_A_MILLI

    override fun planned(
        work: Work,
        planNo: Long,
        due: Instant,
        since: Instant,
        reasons: Map<String, Int>,
        asked: Boolean,
    ) {
        db.insertInto(QUEUED_WORK)
            .set(QUEUED_WORK.WORK_KEY, key(work))
            .set(QUEUED_WORK.WHAT, work.what)
            .set(QUEUED_WORK.TYPE_ID, work.type)
            .set(QUEUED_WORK.SOURCE_ID, work.source)
            .set(QUEUED_WORK.FACET, work.facet)
            .set(QUEUED_WORK.ITEM, work.item)
            .set(QUEUED_WORK.PLAN_NO, planNo)
            .set(QUEUED_WORK.DUE, due)
            .set(QUEUED_WORK.SINCE, since)
            .set(QUEUED_WORK.REASONS, told(reasons))
            .set(QUEUED_WORK.ASKED, asked)
            .onDuplicateKeyUpdate()
            .set(QUEUED_WORK.PLAN_NO, planNo)
            .set(QUEUED_WORK.DUE, due)
            .set(QUEUED_WORK.SINCE, since)
            .set(QUEUED_WORK.REASONS, told(reasons))
            .set(QUEUED_WORK.ASKED, asked)
            .execute()
    }

    override fun ended(work: Work, planNo: Long) {
        db.deleteFrom(QUEUED_WORK).where(QUEUED_WORK.WORK_KEY.eq(key(work)), QUEUED_WORK.PLAN_NO.eq(planNo)).execute()
    }

    override fun ran(run: FinishedWork) {
        val kind = key(run.work.copy(item = null))
        db.insertInto(WORK_RUN)
            .set(WORK_RUN.KIND_KEY, kind)
            .set(WORK_RUN.WHAT, run.work.what)
            .set(WORK_RUN.TYPE_ID, run.work.type)
            .set(WORK_RUN.SOURCE_ID, run.work.source)
            .set(WORK_RUN.FACET, run.work.facet)
            .set(WORK_RUN.ITEM, run.work.item)
            .set(WORK_RUN.BEGAN, run.began)
            .set(WORK_RUN.ENDED, run.ended)
            .set(WORK_RUN.REASONS, told(run.reasons))
            .set(WORK_RUN.FAILED, run.failed)
            .set(WORK_RUN.GIVEN_UP, run.givenUp)
            .execute()
        // Of the kind, its last runs alone
        val kept =
            db.select(WORK_RUN.ID)
                .from(WORK_RUN)
                .where(WORK_RUN.KIND_KEY.eq(kind))
                .orderBy(WORK_RUN.ENDED.desc(), WORK_RUN.ID.desc())
                .limit(KEPT_RUNS)
                .fetch(WORK_RUN.ID)
        db.deleteFrom(WORK_RUN).where(WORK_RUN.KIND_KEY.eq(kind), WORK_RUN.ID.notIn(kept)).execute()
    }

    override fun runs(): List<FinishedWork> =
        db.selectFrom(WORK_RUN)
            .orderBy(WORK_RUN.ENDED.desc(), WORK_RUN.ID.desc())
            .limit(TOLD_RUNS)
            .fetch()
            .map { r ->
                FinishedWork(
                    Work(r.what!!, r.typeId, r.sourceId, r.facet, r.item),
                    r.began!!,
                    r.ended!!,
                    parsed(r.reasons!!),
                    r.failed!!,
                    r.givenUp!!,
                )
            }
            .reversed()

    override fun expected(work: Work): Long? {
        val last =
            db.select(WORK_RUN.BEGAN, WORK_RUN.ENDED)
                .from(WORK_RUN)
                .where(WORK_RUN.KIND_KEY.eq(key(work.copy(item = null))), WORK_RUN.GIVEN_UP.isFalse)
                .orderBy(WORK_RUN.ENDED.desc(), WORK_RUN.ID.desc())
                .limit(MEAN_OF)
                .fetch()
        if (last.isEmpty()) return null
        return last.sumOf { Duration.between(it.value1()!!, it.value2()!!).toMillis() } / last.size
    }

    override fun left(what: String): List<LeftWork> {
        val rows =
            db.selectFrom(QUEUED_WORK).where(QUEUED_WORK.WHAT.eq(what), QUEUED_WORK.PLAN_NO.lt(firstPlan)).fetch()
        if (rows.isEmpty()) return emptyList()
        db.deleteFrom(QUEUED_WORK)
            .where(QUEUED_WORK.WORK_KEY.`in`(rows.map { it.workKey }), QUEUED_WORK.PLAN_NO.lt(firstPlan))
            .execute()
        return rows.map { r ->
            LeftWork(Work(r.what!!, r.typeId, r.sourceId, r.facet, r.item), parsed(r.reasons!!), r.asked!!)
        }
    }

    /** Once every owner took its works at the start: those of the last run nobody took, told and dropped. */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent::class)
    fun forget() {
        val rest =
            db.select(QUEUED_WORK.WORK_KEY)
                .from(QUEUED_WORK)
                .where(QUEUED_WORK.PLAN_NO.lt(firstPlan))
                .fetch(QUEUED_WORK.WORK_KEY)
        if (rest.isEmpty()) return
        log.warn("works the last run left that nobody does again: {}", rest.map { it?.replace('\t', '/') })
        db.deleteFrom(QUEUED_WORK).where(QUEUED_WORK.PLAN_NO.lt(firstPlan)).execute()
    }

    private companion object {
        /** The runs kept of every kind of work, and those whose mean a work to come is expected to take. */
        const val KEPT_RUNS = 5
        const val MEAN_OF = 3

        /** The last runs the interface is told of. */
        const val TOLD_RUNS = 5

        /** The plans of a millisecond a run of the application may make before its numbers reach the next one's. */
        const val PLANS_A_MILLI = 1000L

        fun key(work: Work): String =
            listOf(work.what, work.type, work.source, work.facet, work.item).joinToString("\t") { it.orEmpty() }

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
