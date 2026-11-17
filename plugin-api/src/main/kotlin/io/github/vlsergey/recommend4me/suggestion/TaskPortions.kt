package io.github.vlsergey.recommend4me.suggestion

/**
 * THE ITEMS OF A FACET A PORTION AT A TIME, what a suggester fits on ([FacetSuggester.fit]): no portion holds more than
 * [MOST] items, and nothing holds every item of a source at once — of a million works (user, 2026-11-17).
 *
 * [values]: the facet's value side, alike for every portion — a task of no item: its values, their names and keys,
 * their groupings and hints, the keys of the context; what a model is made of beside what was fitted
 * ([FittedSuggester.model]) and a state unpacked against ([FacetSuggester.unpack]).
 *
 * [forEach]: every portion in turn — a [SuggestionTask] of its items, of the same value side — the same items in the
 * same portions in the same order at every walk; walked as often as the fitting needs, each walk read anew by the
 * application. A portion is the caller's while [forEach] hands it over alone: what is kept of it is copied.
 */
interface TaskPortions {
    val values: SuggestionTask

    /** How many items in all the portions hold. */
    val items: Int

    fun forEach(action: (SuggestionTask) -> Unit)

    companion object {
        /** The most items a portion holds. */
        const val MOST = 10_000

        /**
         * The portions of the items of one [task] of [MOST] items at most: a task read whole already — a test's, a
         * comparison's of few works — handed over as portions, the value side of no item.
         */
        fun of(task: SuggestionTask): TaskPortions =
            object : TaskPortions {
                override val values: SuggestionTask = task.ofItems(IntArray(0))
                override val items: Int = task.itemCount

                override fun forEach(action: (SuggestionTask) -> Unit) {
                    var from = 0
                    while (from < task.itemCount) {
                        val to = minOf(from + MOST, task.itemCount)
                        action(task.ofItems(IntArray(to - from) { from + it }))
                        from = to
                    }
                    if (task.itemCount == 0) action(task)
                }
            }
    }
}
