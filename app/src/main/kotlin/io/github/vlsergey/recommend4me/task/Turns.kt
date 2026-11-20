package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskPriority
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WHOSE TURN IT IS, of the works of the [Tasks]: the works waiting their turn, by their priorities ([ORDER]), of one
 * priority in the order they are asked; and the thread holding the turn. A work alike one still waiting joins it
 * instead of waiting a second time.
 */
internal class Turns {
    /**
     * Guards whose turn it is: the [waiting], and the thread holding the turn ([holder]). [turn] wakes the waiting when
     * the turn is free or the waiting change.
     */
    private val lock = ReentrantLock()

    /** The application stops: no turn is taken any more ([close]). */
    @Volatile
    var closed = false
        private set

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
     * The [work] put among the waiting, of the [priority], going on from the [cursor] — or, when [joinAlike], joined to
     * a work alike still waiting: its [reasons] added to that one's, that one put ahead to its [priority] and going on
     * from the earlier cursor. The waiter, and whether it is the caller's own.
     */
    fun enter(
        work: Task,
        reasons: Map<String, Int>,
        joinAlike: Boolean,
        priority: TaskPriority,
        cursor: TaskCursor? = null,
    ): Pair<Waiter, Boolean> = lock.withLock {
        val alike = if (joinAlike) joinedTo(work, reasons, priority, cursor) else null
        alike?.let { it to false }
            ?: (Waiter(work, Instant.now(), reasons, joinAlike, priority, cursor).also { waiting += it } to true)
    }

    /**
     * Whether a work alike the [work] waits its turn, not begun: then the [work] is that one — its [reasons] added to
     * it, put ahead to the [priority], going on from the earlier [cursor] — and need not be done again: it reads the
     * data as they are when it begins.
     */
    fun joined(work: Task, reasons: Map<String, Int>, priority: TaskPriority, cursor: TaskCursor?): Boolean =
        lock.withLock {
            joinedTo(work, reasons, priority, cursor) != null
        }

    /** The work alike the [work] still waiting, joined by it as [joined] tells; none. Under [lock]. */
    private fun joinedTo(work: Task, reasons: Map<String, Int>, priority: TaskPriority, cursor: TaskCursor?): Waiter? {
        val alike = waiting.firstOrNull { it.joinable && it.work == work } ?: return null
        alike.reasons = merged(alike.reasons, reasons)
        alike.cursor = TaskCursor.earlier(alike.cursor, cursor)
        if (priority < alike.priority) {
            alike.priority = priority
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

    /** The works waiting their turn, in the order they will run ([ORDER]). */
    fun waiting(): List<RunningTask> = lock.withLock {
        waiting.sortedWith(ORDER).map {
            RunningTask(it.work, it.since, it.reasons, it.priority, it.cursor)
        }
    }

    /** Whether a work of the [priority] waits its turn. */
    fun waitsOf(priority: TaskPriority): Boolean = lock.withLock { waiting.any { it.priority == priority } }

    /** Whether the [work] waits its turn, or holds it. */
    fun waits(work: Task): Boolean = lock.withLock { held?.work == work || waiting.any { it.work == work } }

    /** Whose turn comes next ([order]). Under [lock]. */
    private fun next(): Waiter? = waiting.minWithOrNull(ORDER)

    companion object {
        /**
         * The order of the turns: by the priorities ([TaskPriority]) alone, however long a task of a lower one waits
         * (owner, 2026-11-19: "we remove the condition and the logic of the 2 minutes") — of one priority the longest
         * waiting first: a task planned again goes after those of its priority planned before it.
         */
        val ORDER: Comparator<Waiter> = compareBy<Waiter> { it.priority.ordinal }.thenBy { it.since }

        /** The reasons [a] and [b] together, each counted as many times as both tell it. */
        fun merged(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> =
            a.toMutableMap().apply { b.forEach { (k, n) -> merge(k, n, Int::plus) } }
    }
}

/**
 * A work waiting its turn: its reasons grow as works alike join it — put ahead when one of a higher priority does,
 * going on from the earlier cursor — its callers wait for its [result].
 */
internal class Waiter(
    val work: Task,
    val since: Instant,
    @Volatile var reasons: Map<String, Int>,
    val joinable: Boolean,
    @Volatile var priority: TaskPriority,
    @Volatile var cursor: TaskCursor? = null,
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
