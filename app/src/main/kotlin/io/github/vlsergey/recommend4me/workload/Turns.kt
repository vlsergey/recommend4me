package io.github.vlsergey.recommend4me.workload

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WHOSE TURN IT IS, of the works of the [Workload]: the works waiting their turn, in the order they are asked — those
 * the user asked for first, and a work of the background waiting longer than [AGING] counted among them ([order]) — and
 * the thread holding the turn. A work alike one still waiting joins it instead of waiting a second time.
 */
internal class Turns {
    /**
     * Guards whose turn it is: the [waiting], and the thread holding the turn ([holder]). [turn] wakes the waiting when
     * the turn is free or the waiting change.
     */
    private val lock = ReentrantLock()

    /** The application stops: no turn is taken any more ([close]). */
    @Volatile private var closed = false

    /**
     * No turn taken any more: a work waiting or planned is given up as its turn comes, done again at the next start.
     */
    fun close() {
        closed = true
    }

    private val turn = lock.newCondition()
    private var holder: Thread? = null

    /** The work holding the turn: taken from the waiting at once, under [lock]. */
    private var held: Waiter? = null

    /** The works waiting their turn, in the order they are asked; guarded by [lock]. Whose turn comes: [next]. */
    private val waiting = ArrayList<Waiter>()

    /** Whether the current thread holds the turn: a work asking for another inside it, whose turn is its own. */
    fun heldHere(): Boolean = lock.withLock { holder === Thread.currentThread() }

    /**
     * The [work] put among the waiting — or, when [joinAlike], joined to a work alike still waiting: its [reasons]
     * added to that one's, and that one put ahead when [asked]. The waiter, and whether it is the caller's own.
     */
    fun enter(work: Work, reasons: Map<String, Int>, joinAlike: Boolean, asked: Boolean): Pair<Waiter, Boolean> =
        lock.withLock {
            val alike = if (joinAlike) joinedTo(work, reasons, asked) else null
            alike?.let { it to false }
                ?: (Waiter(work, Instant.now(), reasons, joinAlike, asked).also { waiting += it } to true)
        }

    /**
     * Whether a work alike the [work] waits its turn, not begun: then the [work] is that one — its [reasons] added to
     * it, put ahead when [asked] — and need not be done again: it reads the data as they are when it begins.
     */
    fun joined(work: Work, reasons: Map<String, Int>, asked: Boolean): Boolean = lock.withLock {
        joinedTo(work, reasons, asked) != null
    }

    /** The work alike the [work] still waiting, joined by it as [joined] tells; none. Under [lock]. */
    private fun joinedTo(work: Work, reasons: Map<String, Int>, asked: Boolean): Waiter? {
        val alike = waiting.firstOrNull { it.joinable && it.work == work } ?: return null
        alike.reasons = merged(alike.reasons, reasons)
        if (asked && !alike.asked) {
            alike.asked = true
            turn.signalAll()
        }
        return alike
    }

    /**
     * Waits till it is the turn of [entry], the caller's own, and takes it. A wait given up — the thread interrupted —
     * fails the callers joined to it as well.
     */
    @Suppress("TooGenericExceptionCaught")
    fun take(entry: Waiter) {
        try {
            lock.withLock {
                try {
                    while (holder != null || next() !== entry) turn.await()
                    if (closed) throw CancellationException("the application stops")
                    holder = Thread.currentThread()
                    held = entry
                } finally {
                    waiting -= entry
                    // Gone from the waiting — its turn taken, or given up: whose turn it is now is asked anew
                    turn.signalAll()
                }
            }
        } catch (e: Throwable) {
            // A RELAY, NOT A CATCH: whatever ends the wait is handed to the callers joined to the work and thrown on,
            // so that none of them waits for a work that will never run
            entry.result.completeExceptionally(e)
            throw e
        }
    }

    /** The turn given back by the work that held it: whose turn it is now is asked anew. */
    fun release() = lock.withLock {
        holder = null
        held = null
        turn.signalAll()
    }

    /**
     * The works waiting their turn, in the order they will run: those the user asked for and those waiting longer than
     * [AGING] first.
     */
    fun waiting(): List<HeavyWork> = lock.withLock {
        waiting.sortedWith(order(Instant.now())).map { HeavyWork(it.work, it.since, it.reasons, it.asked) }
    }

    /** Whether the [work] waits its turn, or holds it. */
    fun waits(work: Work): Boolean = lock.withLock { held?.work == work || waiting.any { it.work == work } }

    /**
     * Whose turn comes next: of the works the user asked for and those waiting too long the longest waiting, else of
     * the rest. Under [lock].
     */
    private fun next(): Waiter? = waiting.minWithOrNull(order(Instant.now()))

    companion object {
        /**
         * How long a work of the background waits before it goes as one the user asked for (user, 2026-11-15): the
         * trainings the user's answers asked for, one after another while the user marks, kept the works of the
         * background waiting for 14 minutes — the texts of a work just read, its page waiting on them, among them.
         */
        val AGING: Duration = Duration.ofMinutes(2)

        /**
         * The order of the turns at [now]: the works the user asked for and those of the background waiting longer than
         * [AGING] first, each kind the longest waiting first.
         */
        fun order(now: Instant): Comparator<Waiter> =
            compareBy<Waiter> { !it.asked && Duration.between(it.since, now) <= AGING }.thenBy { it.since }

        /** The reasons [a] and [b] together, each counted as many times as both tell it. */
        fun merged(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> =
            a.toMutableMap().apply { b.forEach { (k, n) -> merge(k, n, Int::plus) } }
    }
}

/**
 * A work waiting its turn: its reasons grow as works alike join it — put ahead when one the user asked for does — its
 * callers wait for its [result].
 */
internal class Waiter(
    val work: Work,
    val since: Instant,
    @Volatile var reasons: Map<String, Int>,
    val joinable: Boolean,
    @Volatile var asked: Boolean,
) {
    val result = CompletableFuture<Any?>()

    /** What the work answered, once it is done, to a caller joined to it; its failure thrown here too. */
    @Suppress("UNCHECKED_CAST")
    fun <T> answer(): T =
        try {
            result.get() as T
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
}
