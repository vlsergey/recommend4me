package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import java.time.Instant

/** A work the last run of the application left undone: planned, waiting its turn or running; why, and if asked. */
data class LeftTask(val work: Task, val reasons: Map<String, Int>, val asked: Boolean)

/**
 * WHERE THE QUEUE OF THE [Tasks] IS KEPT: every work not done yet, a row from its plan to its end, and the last runs of
 * every kind of work — so a stop or a crash leaves the works undone to the next start, and what a work is expected to
 * take outlives the process (user, 2026-11-18: "why not just use the database as the store of the queue?"; "the
 * expected timings there too, the last three runs to take their mean"). What runs, its code and its timer, is the
 * process's own: a work of the last run is planned again by its owner ([left]). [NONE] keeps nothing: a queue alone.
 */
interface TaskRecords {
    /** The first plan's number of this run of the application: those of the runs before are below. */
    val firstPlan: Long

    /** The [work] planned, its plan [planNo], for [due], since [since], for the [reasons], asked or not. */
    fun planned(work: Task, planNo: Long, due: Instant, since: Instant, reasons: Map<String, Int>, asked: Boolean)

    /** The [work] of the plan [planNo] ended — done, failed or given up: its row gone, not a newer plan's. */
    fun ended(work: Task, planNo: Long)

    /** A run of a work ended, kept among the last of its kind. */
    fun ran(run: FinishedTask)

    /** The last runs, the oldest first. */
    fun runs(): List<FinishedTask>

    /** How long a work of the [work]'s kind is expected to take: the mean of its last runs not given up; or none. */
    fun expected(work: Task): Long?

    /** The works of the kind [what] the last run of the application left undone: taken, kept no more. */
    fun left(what: TaskKind): List<LeftTask>

    /**
     * The [keys] the [work] is to be done of — the items, the fandoms its owner names — kept till they are [done]: a
     * stop leaves them to the work planned again at the next start.
     */
    fun want(work: Task, keys: Collection<String>)

    /** The keys the [work] is to be done of, as kept. */
    fun wanted(work: Task): List<String>

    /** The [keys] of the [work] done: kept no more — those wanted since stay. */
    fun done(work: Task, keys: Collection<String>)

    /** Nothing kept between the runs: the keys of the works in the process alone. */
    object NONE : TaskRecords {
        private val keys = HashMap<Task, MutableSet<String>>()
        override val firstPlan = 0L

        override fun planned(
            work: Task,
            planNo: Long,
            due: Instant,
            since: Instant,
            reasons: Map<String, Int>,
            asked: Boolean,
        ) = Unit

        override fun ended(work: Task, planNo: Long) = Unit

        override fun ran(run: FinishedTask) = Unit

        override fun runs(): List<FinishedTask> = emptyList()

        override fun expected(work: Task): Long? = null

        override fun left(what: TaskKind): List<LeftTask> = emptyList()

        override fun want(work: Task, keys: Collection<String>) {
            synchronized(this.keys) { this.keys.getOrPut(work) { LinkedHashSet() } += keys }
        }

        override fun wanted(work: Task): List<String> = synchronized(keys) { keys[work]?.toList().orEmpty() }

        override fun done(work: Task, keys: Collection<String>) {
            synchronized(this.keys) { this.keys[work]?.removeAll(keys.toSet()) }
        }
    }
}
