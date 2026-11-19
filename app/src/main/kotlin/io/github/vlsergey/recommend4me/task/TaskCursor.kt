package io.github.vlsergey.recommend4me.task

/**
 * WHERE A TASK OVER THE WORKS GOES ON FROM (owner, 2026-11-19: "with a shift of the starting grade and the work's id to
 * the last processed"): the works go in the order of their expected grade, the highest first, those of one grade by
 * their keys; the task goes on after the work [work] of the expected [grade]. No [grade] is above every grade — the
 * very first work on (owner: "the absence is plus infinity"); a work of no expectation is of the grade
 * [Double.NEGATIVE_INFINITY], after every other. Planned again before it began, a task goes on from the earlier of its
 * two cursors ([earlier]): a work changed above the one it went on from is not left behind.
 */
data class TaskCursor(val grade: Double? = null, val work: String? = null) {
    companion object {
        /** From the very first work. */
        val FIRST = TaskCursor()

        /** The order of the works a task goes over: the highest expected grade first, then by the works' keys. */
        val ORDER: Comparator<TaskCursor> =
            compareBy<TaskCursor> { -(it.grade ?: Double.POSITIVE_INFINITY) }.thenBy(nullsFirst()) { it.work }

        /** Of the cursors [a] and [b], the one a task goes on from sooner; of a task of none, none. */
        fun earlier(a: TaskCursor?, b: TaskCursor?): TaskCursor? =
            when {
                a == null -> b
                b == null -> a
                else -> minOf(a, b, ORDER)
            }
    }
}
