package io.github.vlsergey.recommend4me.workload

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * WHAT IS DONE, of the works of the [Workload]: the last [FINISHED] works, with how long each took, and of every kind
 * of work — a work of any item alike ([kind]) — how long its last run took: what a work to come is expected to take
 * ([expected]). In memory, by the user's word (2026-10-15): a few dozen numbers, forgotten on a restart — till a kind
 * runs again, it has no estimate.
 */
internal class WorksDone {
    /** The last works done, the oldest first; guarded by itself. */
    private val finished = ArrayDeque<FinishedWork>()

    /** How long the last run of every kind of work took. */
    private val lastMillis = ConcurrentHashMap<Work, Long>()

    /**
     * The work [entry] ended: kept among the works done, with how long it took — of a work given up, a part of it, no
     * estimate.
     */
    fun add(entry: HeavyWork, failed: Boolean, givenUp: Boolean) {
        val ended = Instant.now()
        if (!givenUp) lastMillis[kind(entry.work)] = Duration.between(entry.since, ended).toMillis()
        synchronized(finished) {
            finished.addLast(FinishedWork(entry.work, entry.since, ended, entry.reasons, failed, givenUp))
            while (finished.size > FINISHED) finished.removeFirst()
        }
    }

    /**
     * How long the [work] is expected to take: as long as the last run of its kind; null when none ran since the start.
     */
    fun expected(work: Work): Long? = lastMillis[kind(work)]

    /** The last works done, the oldest first. */
    fun list(): List<FinishedWork> = synchronized(finished) { finished.toList() }

    /** The kind of a work: alike for every item — what the work of another item took, this one is expected to take. */
    private fun kind(work: Work) = work.copy(item = null)

    private companion object {
        /** How many works done are told. */
        const val FINISHED = 5
    }
}
