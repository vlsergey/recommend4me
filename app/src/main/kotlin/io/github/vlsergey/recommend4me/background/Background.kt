package io.github.vlsergey.recommend4me.background

import java.time.Duration

/**
 * WHERE THE APPLICATION DOES WHAT IT DOES OFF A REQUEST — the works of the queue begun when they are due, the jobs of
 * the sites, the rounds done again and again, the loops as long as the application runs. Nothing of the application
 * starts a thread or a timer of its own: it asks here. In the application these are threads ([Threads], made by
 * [BackgroundThreads]); in the tests nothing runs by itself — what is started is held till the test runs it, at once,
 * on its own (user, 2026-11-18: "every background task is off in the tests"; "a test says 'now the test task', and it
 * runs at once in that test").
 */
interface Background {
    /** [task] begun at once on a thread of its own, named [name]. */
    fun start(name: String, task: () -> Unit): Started

    /** [task] begun after [delay], as [start] begins it; not begun if cancelled before. */
    fun later(name: String, delay: Duration, task: () -> Unit): Later

    /**
     * [task] done again and again as long as the application runs: the first time after [first], each next [period]
     * after the last ended. A failure of a round is told in the log, and the next round done all the same.
     */
    fun every(name: String, first: Duration, period: Duration, task: () -> Unit): Rounds

    /** [task] a loop of its own as long as the application runs, on a thread of its own: it ends as it is told to. */
    fun endless(name: String, task: () -> Unit): Started
}

/** A task begun: waited for, or interrupted. */
interface Started {
    /** Waits up to [timeout] for the task to end; whether it did. */
    fun await(timeout: Duration): Boolean

    /** The task's thread interrupted. */
    fun interrupt()
}

/** A task to begin later: not begun if [cancel]led before. */
fun interface Later {
    fun cancel()
}

/** Rounds done again and again: no other begun once [stop]ped, the one under way waited for up to [wait]. */
fun interface Rounds {
    fun stop(wait: Duration)
}
