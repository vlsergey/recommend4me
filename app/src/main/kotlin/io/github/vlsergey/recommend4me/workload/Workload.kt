package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.api.model.WorkReasonKey
import io.github.vlsergey.recommend4me.background.Background
import io.github.vlsergey.recommend4me.background.Threads
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Workload::class.java)

/**
 * What a work does — [what], a key the interface words as "workload.<what>" — and of what: a content type, a source, a
 * facet, a work. Two works alike are the same work: planned twice, it is planned once.
 */
data class Work(
    val what: WorkKind,
    val type: String? = null,
    val source: String? = null,
    val facet: String? = null,
    val item: String? = null,
) {
    override fun toString() =
        listOfNotNull(type, source, facet, item).joinToString("/").ifEmpty { "all" } + ": " + what.value
}

/**
 * A work waiting or running: since when, and why — the changes that asked for it, by the key the interface words as
 * "workload.reason.<key>", each with how many times it came — and whether the user's action in the interface asked for
 * it ([asked]: it waits ahead of the background).
 */
class HeavyWork(val work: Work, val since: Instant, val reasons: Map<String, Int>, val asked: Boolean = false) {
    /** Of a work that tells it ([Workload.progress]), how much of it is done: a share; none of one that does not. */
    @Volatile var progress: Double? = null
}

/**
 * A work planned for later: when it is due (it may be put off by newer changes), since when it is planned, why, and
 * whether the user asked for it.
 */
class PlannedWork(
    val work: Work,
    val due: Instant,
    val since: Instant,
    val reasons: Map<String, Int>,
    val asked: Boolean = false,
)

/**
 * A work done: when it began and ended, why it ran, whether it failed — or was [givenUp]: newer data came while it ran,
 * and it is done again on them (a [CancellationException]), no failure.
 */
class FinishedWork(
    val work: Work,
    val began: Instant,
    val ended: Instant,
    val reasons: Map<String, Int>,
    val failed: Boolean,
    val givenUp: Boolean = false,
)

/**
 * EVERY COMPUTATION OF THE BACKGROUND, ONE AT A TIME, IN THE ORDER ASKED, EACH WHOLE — THE USER'S FIRST: a work the
 * user's action in the interface asked for — the search of the duplicates, the model of the pairs after an answer, a
 * training after a grade, what a model that had not seen a work says of it, a comparison pressed — waits ahead of every
 * work of the background, those among themselves in the order asked (user, 2026-11-07); a work running is never stopped
 * for it. The training of the grades and of interest, a model that had not seen a graded work, the suggesters of the
 * facets and the chances after the user's answers, the comparisons of methods, the counting and the vectors of the
 * tags, the reranker reading pairs, the vectors of texts, chapters, pictures and sets, the index of the search. The
 * heavy ones read 27 718 games with their vectors, a gigabyte and more; eight at once — a training in the background,
 * the held-out models of the pages opened meanwhile — ran the heap out (2026-10-15); and the user wants no computation
 * beside another (2026-10-15). Whoever asks waits its turn here — a background thread, never a request of the browser
 * ([Requests]): a request plans the work and answers with what there is. Only what is not a computation goes outside
 * it: reading pages and pictures from the sites, compacting files.
 *
 * Reentrant: a work asking for another inside it goes on. Taken before any lock of its own — a lock held while waiting
 * here would hold up whoever runs.
 *
 * A work of the background waiting longer than [Turns.AGING] goes as one the user asked for: a stream of the user's
 * works never keeps the background waiting for ever.
 *
 * AND WHAT IS PLANNED, AND WHY — THE ONE WAY THE BACKGROUND IS SCHEDULED: the works after the changes that ask for them
 * ([plan]) — a grade, an answer, a tag — are planned here with their reasons and what they do, begun here when due
 * ([Plans]); planned again before they begin, they are done once. What a work is, when it is due, since when and why is
 * kept in the [records] from its plan to its end: a work a stop or a crash left undone is planned again at the next
 * start by its owner, of its kind ([left]). The runs of the works are kept there too ([WorkRecords.runs],
 * [WorkRecords.expected]).
 */
@Component
class Workload(background: Background = Threads(), private val records: WorkRecords = WorkRecords.NONE) {
    /** Whose turn it is: the works waiting their turn and the thread holding it. */
    private val turns = Turns()
    private val plans = Plans(turns, background, records)

    @Volatile private var current: HeavyWork? = null

    /**
     * THE APPLICATION STOPS, BEFORE ITS FILES CLOSE: no work planned or waiting begins any more, and the one running is
     * waited for up to [wait] — never a database closed under a transaction of a work (2026-11-18: a work running on as
     * the files closed left one unfinished, and the file written anew at the next start would not open).
     */
    fun close(wait: Duration) {
        turns.close()
        val until = System.nanoTime() + wait.toNanos()
        while (current != null && System.nanoTime() < until) Thread.sleep(CLOSE_LOOK_MS)
        current?.let { log.warn("{} still running as the application stops", it.work) }
    }

    /**
     * Runs [body] once every work asked for before it is done; answers what it answers. Its [reasons]; when null, those
     * of the work planned alike, if any — which it is then, no longer planned. Inside another work it is a part of that
     * one: a work planned alike stays planned, made of changes the running one may have read before.
     *
     * ALIKE WORKS ARE ONE: asked while a work alike still waits its turn, it is not queued a second time — the caller
     * waits for that one and answers what it answers, its reasons added to it; it begins on the data as they are then,
     * the newer change too. A work alike already running is not joined: it read the data before the change, so the work
     * is queued again. A caller whose work is more than its [Work] tells — a batch of its own ids — passes [joinAlike]
     * false.
     *
     * [asked]: the user's action in the interface asked for it — it waits ahead of every work that was not; a work
     * alike it joins is put ahead with it.
     */
    fun <T> run(
        work: Work,
        reasons: Map<String, Int>? = null,
        joinAlike: Boolean = true,
        asked: Boolean = false,
        body: () -> T,
    ): T {
        Requests.refuseWaiting(work)
        // A work asking for another inside it goes on: the turn is its own
        if (turns.heldHere()) return body()
        val plan = if (reasons == null) plans.take(work) else null
        val (entry, own) = turns.enter(work, reasons ?: plan?.reasons.orEmpty(), joinAlike, asked)
        return try {
            if (own) runOwn(entry, body) else entry.answer()
        } finally {
            plan?.let(plans::ended)
        }
    }

    /**
     * Runs [body] as the work [entry], the caller's own, once its turn comes: what it answers, or its failure, handed
     * to the callers joined to it as well; the run kept among the works done, and the turn given back after it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> runOwn(entry: Waiter, body: () -> T): T {
        turns.take(entry)
        try {
            val waited = Duration.between(entry.since, Instant.now())
            if (waited > NOTED) log.info("{} waited {} s for the works before it", entry.work, waited.toSeconds())
            val running = HeavyWork(entry.work, Instant.now(), entry.reasons, entry.asked)
            current = running
            var failed = true
            var givenUp = false
            try {
                return body().also {
                    failed = false
                    entry.result.complete(it)
                }
            } catch (e: CancellationException) {
                failed = false
                givenUp = true
                entry.result.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                // Of the application stopping — its files closed under the work — the work is given up, not failed: it
                // stays in the queue and is done at the next start (owner, 2026-11-19: "errors in the queue")
                if (turns.closed) {
                    failed = false
                    givenUp = true
                }
                // A RELAY, NOT A CATCH: whatever the work throws is handed to the callers joined to it and thrown on
                entry.result.completeExceptionally(e)
                throw e
            } finally {
                records.ran(FinishedWork(running.work, running.since, Instant.now(), running.reasons, failed, givenUp))
            }
        } finally {
            current = null
            turns.release()
        }
    }

    /**
     * What each work of the queue is to be done of — the items, the fandoms its owner names — kept with it till done
     * ([WorkKeys]): a stop leaves them to the work planned again at the next start.
     */
    val keys = WorkKeys(records)

    /**
     * The works of the kind [what] the last run of the application left undone — planned, waiting or running as it
     * stopped or crashed: taken by their owner at the start, to plan again.
     */
    fun left(what: WorkKind): List<LeftWork> = records.left(what)

    /** The works of the kind [what] the last run left undone planned again by [plan]: each for each of its reasons. */
    fun resume(what: WorkKind, plan: (left: LeftWork, reason: String) -> Unit) =
        left(what).forEach { l -> l.reasons.keys.forEach { plan(l, it) } }

    /**
     * The [work] planned for [due] — later than planned before, if put off; when null, as planned before, else at once
     * — for one more [reason], [count] times; ahead of the background when the user [asked] for it, after all of it
     * when it goes [last]. When its turn comes, [body] runs, and [after] of what it answers once the turn is given
     * back: what is told of it — an event whose listeners may do works of their own — is told out of the queue. Planned
     * again before it begins, the newer [body] is the one run. A failure of either is told in the log.
     */
    fun <T> plan(
        work: Work,
        due: Instant?,
        reason: String,
        count: Int = 1,
        asked: Boolean = false,
        last: Boolean = false,
        after: (T) -> Unit = {},
        body: () -> T,
    ) =
        plans.plan(work, due, mapOf(WorkReasonKey.forValue(reason).value to count), asked, last) { entry ->
            after(runOwn(entry, body))
        }

    /**
     * How much of the work running now is done, told by it as it goes: a share (user, 2026-11-18: "a report of the
     * progress in per cents").
     */
    fun progress(share: Double) {
        current?.progress = share.coerceIn(0.0, 1.0)
    }

    /**
     * Whether the application stops ([close]): a long work asks it between its steps, and gives up with a
     * [CancellationException] — its record kept, it is done again at the next start — before the files close under it.
     */
    val stopping: Boolean
        get() = turns.closed

    /** The work running now, none when idle. */
    fun running(): HeavyWork? = current

    /** The works waiting their turn, in the order they will run: those the user asked for first. */
    fun waiting(): List<HeavyWork> = turns.waiting()

    /**
     * Whether the [work] is to be done: planned, waiting its turn or — when [running] counts — running now: what it
     * makes is not made yet. Not of one running: it read the data before, and is done again once planned so.
     */
    fun due(work: Work, running: Boolean = true): Boolean =
        plans.has(work) || turns.waits(work) || (running && current?.work == work)

    /** The works planned, the soonest due first. */
    fun planned(): List<PlannedWork> = plans.list()

    private companion object {
        /** A wait worth telling in the log. */
        val NOTED: Duration = Duration.ofSeconds(2)

        /** How often the stop looks whether the work running has ended. */
        const val CLOSE_LOOK_MS = 50L
    }
}
