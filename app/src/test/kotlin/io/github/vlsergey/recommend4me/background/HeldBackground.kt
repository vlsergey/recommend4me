package io.github.vlsergey.recommend4me.background

import io.github.vlsergey.recommend4me.task.inBackground
import java.time.Duration

/**
 * THE BACKGROUND OF THE TESTS: NOTHING RUNS BY ITSELF (user, 2026-11-18: "every background task is off in the tests";
 * "no line 'now we wait 5 seconds for a background task' in a test — a test says 'now the test task', and it runs at
 * once in that test"). What the application starts or begins later is held, in the order it came, till the test says
 * [run]: then it runs at once, whole, with whatever it starts in turn. The rounds done again and again and the loops as
 * long as the application runs are never run: no test's scenario stands on them.
 */
class HeldBackground : Background {
    private class Held(val name: String, val task: () -> Unit)

    /** What was started and not run yet, in order; guarded by itself. */
    private val held = ArrayDeque<Held>()

    override fun start(name: String, task: () -> Unit): Started {
        hold(Held(name, task))
        return DONE_WITH
    }

    override fun later(name: String, delay: Duration, task: () -> Unit): Later {
        val one = Held(name, task)
        hold(one)
        return Later { synchronized(held) { held.remove(one) } }
    }

    override fun every(name: String, first: Duration, period: Duration, task: () -> Unit) = Rounds {}

    override fun endless(name: String, task: () -> Unit): Started = DONE_WITH

    private fun hold(one: Held) = synchronized(held) { held.addLast(one) }

    /**
     * EVERYTHING HELD RUN NOW, and what it starts, till nothing is held: on a thread of its own, as the background runs
     * it — the thread of a test stands for a request of the browser, which never waits for a computation. Whatever a
     * task throws is thrown here. The names run, in order.
     */
    fun run(): List<String> = inBackground {
        val ran = ArrayList<String>()
        while (true) {
            val next = synchronized(held) { held.removeFirstOrNull() } ?: break
            check(ran.size < MOST) {
                "more than $MOST tasks run, a task starting itself again and again: ${ran.takeLast(20)}"
            }
            ran += next.name
            next.task()
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
