package io.github.vlsergey.recommend4me.background

import io.github.vlsergey.recommend4me.task.inBackground
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * THE BACKGROUND OF THE TESTS: NOTHING RUNS BY ITSELF (user, 2026-11-18: "every background task is off in the tests";
 * "no line 'now we wait 5 seconds for a background task' in a test — a test says 'now the test task', and it runs at
 * once in that test"). What the application starts or begins later is held, in the order it came, till the test says
 * [run]: then it runs at once, whole, with whatever it starts in turn. The rounds done again and again and the loops as
 * long as the application runs are never run: no test's scenario stands on them.
 */
class HeldBackground : Background {
    private class Held(val name: String, val task: () -> Unit)

    /** What was started and not run yet, in order; guarded by [lock]. */
    private val held = ArrayDeque<Held>()

    /** How many held tasks run now, of every caller of [run]; guarded by [lock]. */
    private var running = 0

    private val lock = ReentrantLock()

    /** Something was held, or a task ended: whoever waits looks again. */
    private val changed = lock.newCondition()

    override fun start(name: String, task: () -> Unit): Started {
        hold(Held(name, task))
        return DONE_WITH
    }

    override fun later(name: String, delay: Duration, task: () -> Unit): Later {
        val one = Held(name, task)
        hold(one)
        return Later { lock.withLock { held.remove(one) } }
    }

    override fun every(name: String, first: Duration, period: Duration, task: () -> Unit) = Rounds {}

    override fun endless(name: String, task: () -> Unit): Started = DONE_WITH

    private fun hold(one: Held) = lock.withLock {
        held.addLast(one)
        changed.signalAll()
    }

    /**
     * EVERYTHING HELD RUN NOW, and what it starts, till nothing is held: on a thread of its own, as the background runs
     * it — the thread of a test stands for a request of the browser, which never waits for a computation. Whatever a
     * task throws is thrown here. The names run, in order.
     *
     * THE CLASSES OF THE TESTS RUN SIDE BY SIDE in the one application: a task held of this test may be taken by
     * another's run, and run there. So a run ends when nothing is held and no task taken by any run is running — what
     * that one starts comes back to be run here too (2026-11-19: a test's run ended while the encoding of its work ran
     * in another test's, and it read the work before its texts were encoded).
     */
    fun run(): List<String> = inBackground {
        val ran = ArrayList<String>()
        while (true) {
            val next =
                lock.withLock {
                    while (held.isEmpty() && running > 0) changed.await()
                    held.removeFirstOrNull()?.also { running++ }
                } ?: break
            check(ran.size < MOST) {
                "more than $MOST tasks run, a task starting itself again and again: ${ran.takeLast(20)}"
            }
            ran += next.name
            try {
                next.task()
            } finally {
                lock.withLock {
                    running--
                    changed.signalAll()
                }
            }
        }
        ran
    }

    private companion object {
        /** More tasks than a scenario starts: one starting itself again and again. */
        const val MOST = 10_000

        /** A task held, or never run: nothing to wait for, nothing to interrupt. */
        val DONE_WITH =
            object : Started {
                override fun await(timeout: Duration) = true

                override fun interrupt() = Unit
            }
    }
}
