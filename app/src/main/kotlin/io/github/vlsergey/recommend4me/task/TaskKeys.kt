package io.github.vlsergey.recommend4me.task

/**
 * WHAT A WORK OF THE QUEUE IS TO BE DONE OF — the items, the fandoms its owner names — kept in the queue with the work
 * till they are done ([TaskRecords.want]): a stop leaves them to the work planned again at the next start, which does
 * what was asked, not everything because what was asked was lost (owner, 2026-11-19: "the queue is kept in the
 * database: nothing is to be run at a start just because it is a start").
 */
class TaskKeys internal constructor(private val records: TaskRecords) {
    /** The [keys] the [work] is to be done of, added to those it has. */
    fun want(work: Task, keys: Collection<String>) = records.want(work, keys)

    /** The keys the [work] is to be done of, as the queue keeps them. */
    fun wanted(work: Task): List<String> = records.wanted(work)

    /** The [keys] of the [work] done: kept no more — those wanted since it read them stay. */
    fun done(work: Task, keys: Collection<String>) = records.done(work, keys)
}
