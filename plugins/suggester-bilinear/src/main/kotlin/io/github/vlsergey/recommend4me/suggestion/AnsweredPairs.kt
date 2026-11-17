package io.github.vlsergey.recommend4me.suggestion

/**
 * THE PAIRS THE USER ANSWERED ON of a portion of the items: of every item its confirmed and its rejected values
 * ([SuggestionTask.confirmed], [SuggestionTask.rejected]) — each pair once, the items ascending, the values of an item
 * ascending. Every pair is scored by the model kept, which scores an item fitted on by the fold that held it out
 * ([ScoredPortions]): what the application calibrates the facet's chances by ([HeldOutScores]).
 */
internal class AnsweredPairs(val items: IntArray, val values: IntArray) {
    val size: Int
        get() = items.size

    companion object {
        /** The answered pairs of [task] of its [m] values. */
        fun of(task: SuggestionTask, m: Int): AnsweredPairs {
            val items = ArrayList<Int>()
            val values = ArrayList<Int>()
            for (i in 0 until task.itemCount) {
                val answered = (task.confirmed[i].asList() + task.rejected[i].asList()).filter { it in 0 until m }
                answered.distinct().sorted().forEach { v ->
                    items += i
                    values += v
                }
            }
            return AnsweredPairs(items.toIntArray(), values.toIntArray())
        }
    }
}
