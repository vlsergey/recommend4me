package io.github.vlsergey.recommend4me.workload

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Workload::class.java)

/**
 * What a work does — [what], a key the interface words as "workload.<what>" — and of what: a content type, a source, a
 * facet, a work. Two works alike are the same work: planned twice, it is planned once.
 */
data class Work(
    val what: String,
    val type: String? = null,
    val source: String? = null,
    val facet: String? = null,
    val item: String? = null,
) {
    override fun toString() = listOfNotNull(type, source, facet, item).joinToString("/").ifEmpty { "all" } + ": " + what
}

/**
 * A work waiting or running: since when, and why — the changes that asked for it, by the key the interface words as
 * "workload.reason.<key>", each with how many times it came — and whether the user's action in the interface asked for
 * it ([asked]: it waits ahead of the background).
 */
class HeavyWork(val work: Work, val since: Instant, val reasons: Map<String, Int>, val asked: Boolean = false)

/** A work planned for later: when it is due (it may be put off by newer changes), since when it is planned, and why. */
class PlannedWork(val work: Work, val due: Instant, val since: Instant, val reasons: Map<String, Int>)

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
 * AND WHAT IS PLANNED, AND WHY: the background works scheduled after the changes that ask for them ([plan]) — a grade,
 * an answer, a tag — are told here with their reasons, which go with them once they begin ([take]). Of the works
 * nothing is kept but what they are, their times and their reasons, and nothing outlives the process.
 *
 * AND WHAT IS DONE: the last [FINISHED] works, with how long each took, and of every kind of work — a work of any item
 * alike ([kind]) — how long its last run took: what a work to come is expected to take ([expected]). In memory, by the
 * user's word (2026-10-15): a few dozen numbers, forgotten on a restart — till a kind runs again, it has no estimate.
 */
@Component
class Workload {
    /**
     * Guards whose turn it is: the [waiting], and the thread holding the turn ([holder]). [turn] wakes the waiting when
     * the turn is free or the waiting change.
     */
    private val lock = ReentrantLock()
    private val turn = lock.newCondition()
    private var holder: Thread? = null

    /** The works waiting their turn, in the order they are asked; guarded by [lock]. Whose turn comes: [next]. */
    private val waiting = ArrayList<Waiter>()
    private val planned = ConcurrentHashMap<Work, PlannedWork>()

    /** The last works done, the oldest first; guarded by itself. */
    private val finished = ArrayDeque<FinishedWork>()

    /** How long the last run of every kind of work took. */
    private val lastMillis = ConcurrentHashMap<Work, Long>()

    @Volatile private var current: HeavyWork? = null

    /**
     * Runs [body] once every work asked for before it is done; answers what it answers. The reasons of the work planned
     * alike, if any, go with it.
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
        reasons: Map<String, Int> = take(work),
        joinAlike: Boolean = true,
        asked: Boolean = false,
        body: () -> T,
    ): T {
        Requests.refuseWaiting(work)
        // A work asking for another inside it goes on: the turn is its own
        if (lock.withLock { holder === Thread.currentThread() }) return body()
        val (entry, own) =
            lock.withLock {
                val alike = if (joinAlike) waiting.firstOrNull { it.joinable && it.work == work } else null
                if (alike != null) {
                    alike.reasons = merged(alike.reasons, reasons)
                    if (asked && !alike.asked) {
                        alike.asked = true
                        turn.signalAll()
                    }
                    alike to false
                } else Waiter(work, Instant.now(), reasons, joinAlike, asked).also { waiting += it } to true
            }
        if (!own) return joined(entry)
        try {
            lock.withLock {
                try {
                    while (holder != null || next() !== entry) turn.await()
                    holder = Thread.currentThread()
                } finally {
                    waiting -= entry
                    // Gone from the waiting — its turn taken, or given up: whose turn it is now is asked anew
                    turn.signalAll()
                }
            }
        } catch (e: Throwable) {
            entry.result.completeExceptionally(e)
            throw e
        }
        try {
            val waited = Duration.between(entry.since, Instant.now())
            if (waited > NOTED) log.info("{} waited {} s for the works before it", work, waited.toSeconds())
            val running = HeavyWork(work, Instant.now(), entry.reasons, entry.asked)
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
                entry.result.completeExceptionally(e)
                throw e
            } finally {
                done(running, failed, givenUp)
            }
        } finally {
            current = null
            lock.withLock {
                holder = null
                turn.signalAll()
            }
        }
    }

    /** Whose turn comes next: of the works the user asked for the longest waiting, else of the rest. Under [lock]. */
    private fun next(): Waiter? = waiting.minWithOrNull(ORDER)

    /** What the work alike [entry] answered, once it is done; its failure thrown here too. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> joined(entry: Waiter): T =
        try {
            entry.result.get() as T
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private fun merged(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> =
        a.toMutableMap().apply { b.forEach { (k, n) -> merge(k, n, Int::plus) } }

    /**
     * A work waiting its turn: its reasons grow as works alike join it — put ahead when one the user asked for does —
     * its callers wait for its [result].
     */
    private class Waiter(
        val work: Work,
        val since: Instant,
        @Volatile var reasons: Map<String, Int>,
        val joinable: Boolean,
        @Volatile var asked: Boolean,
    ) {
        val result = CompletableFuture<Any?>()
    }

    /**
     * The work [entry] ended: kept among the works done, with how long it took — of a work given up, a part of it, no
     * estimate.
     */
    private fun done(entry: HeavyWork, failed: Boolean, givenUp: Boolean) {
        val ended = Instant.now()
        if (!givenUp) lastMillis[kind(entry.work)] = Duration.between(entry.since, ended).toMillis()
        synchronized(finished) {
            finished.addLast(FinishedWork(entry.work, entry.since, ended, entry.reasons, failed, givenUp))
            while (finished.size > FINISHED) finished.removeFirst()
        }
    }

    /** The kind of a work: alike for every item — what the work of another item took, this one is expected to take. */
    private fun kind(work: Work) = work.copy(item = null)

    /**
     * How long the [work] is expected to take: as long as the last run of its kind; null when none ran since the start.
     */
    fun expected(work: Work): Long? = lastMillis[kind(work)]

    /** The last works done, the oldest first. */
    fun finished(): List<FinishedWork> = synchronized(finished) { finished.toList() }

    /**
     * The [work] planned for [due] — later than planned before, if put off; when null, as planned before — for one more
     * [reason].
     */
    fun plan(work: Work, due: Instant?, reason: String) {
        planned.compute(work) { _, old ->
            val reasons = old?.reasons.orEmpty().toMutableMap().apply { merge(reason, 1, Int::plus) }
            PlannedWork(work, due ?: old?.due ?: Instant.now(), old?.since ?: Instant.now(), reasons)
        }
    }

    /** The work begins: no longer planned; the reasons it was planned for, none if it was not. */
    fun take(work: Work): Map<String, Int> = planned.remove(work)?.reasons.orEmpty()

    /** The work running now, none when idle. */
    fun running(): HeavyWork? = current

    /** The works waiting their turn, in the order they will run: those the user asked for first. */
    fun waiting(): List<HeavyWork> = lock.withLock {
        waiting.sortedWith(ORDER).map { HeavyWork(it.work, it.since, it.reasons, it.asked) }
    }

    /** Whether the [work] is to be done: planned, waiting its turn or running now — what it makes is not made yet. */
    fun due(work: Work): Boolean =
        planned.containsKey(work) || lock.withLock { waiting.any { it.work == work } } || current?.work == work

    /** The works planned, the soonest due first. */
    fun planned(): List<PlannedWork> = planned.values.sortedBy { it.due }

    private companion object {
        /** The order of the turns: the works the user asked for first, each kind the longest waiting first. */
        val ORDER: Comparator<Waiter> = compareBy<Waiter> { !it.asked }.thenBy { it.since }

        /** A wait worth telling in the log. */
        val NOTED: Duration = Duration.ofSeconds(2)

        /** How many works done are told. */
        const val FINISHED = 5
    }
}
