package io.github.vlsergey.recommend4me.suggestion

/**
 * THE PAIRS THE USER ANSWERED ON, of a task as the training reads it ([Problem]): of every item its confirmed and its
 * rejected values ([SuggestionTask.confirmed], [SuggestionTask.rejected]) — each pair once, the items ascending, the
 * values of an item ascending — with whether the site gives the value as an example ([examples]: 1 or 0), which its
 * score adds. Every pair is scored by the model of the fold that held its item out, where the folds' held-out loss is
 * worked out: what the application calibrates the facet's chances by ([HeldOutScores]).
 */
internal class AnsweredPairs(val items: IntArray, val values: IntArray, val examples: FloatArray) {
    val size: Int
        get() = items.size

    /**
     * Of the pairs of the items of [fold] — by the fold of every item [foldOf] — the [score] of each into [into] at its
     * place among them: of its place, its item, its value and its example. Those of the other folds left as they are.
     */
    inline fun scoreFold(fold: Int, foldOf: IntArray, into: FloatArray, score: (Int, Int, Int, Float) -> Float) {
        for (k in 0 until size) if (foldOf[items[k]] == fold) into[k] = score(k, items[k], values[k], examples[k])
    }

    /** The pairs with their [scores], in their order: what the fitting hands the application. */
    fun scored(scores: FloatArray): HeldOutScores {
        require(scores.size == size) { "${scores.size} scores of $size answered pairs" }
        return HeldOutScores(items.copyOf(), values.copyOf(), scores.copyOf())
    }

    companion object {
        /** The answered pairs of [task] of its [m] values, the site's examples of each item [exampleValues]. */
        fun of(task: SuggestionTask, m: Int, exampleValues: Array<IntArray>): AnsweredPairs {
            val items = ArrayList<Int>()
            val values = ArrayList<Int>()
            val examples = ArrayList<Float>()
            for (i in 0 until task.itemCount) {
                val answered = (task.confirmed[i].asList() + task.rejected[i].asList()).filter { it in 0 until m }
                answered.distinct().sorted().forEach { v ->
                    items += i
                    values += v
                    examples += if (exampleValues[i].binarySearch(v) >= 0) 1f else 0f
                }
            }
            return AnsweredPairs(items.toIntArray(), values.toIntArray(), examples.toFloatArray())
        }
    }
}
