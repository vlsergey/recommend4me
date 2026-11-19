package io.github.vlsergey.recommend4me.suggestion

import java.util.IdentityHashMap

/**
 * THE VALUES A MODEL WAS NOT MADE OF, IN A TASK IT SCORES ([FacetSuggester.scoring]; owner, 2026-11-19: of the pairings
 * of a work, the pairs of its own characters, which no dictionary lists): the application asks the values the model was
 * made of first, in their order, and after them any others the works may have. A suggester that knows a value by its
 * features scores it by them; one that knows its own values alone scores those of the task of its first values
 * ([ofFirstValues]) and gives the others its least score ([beyond]).
 */
object NewValues {
    /**
     * The [task] of its first [m] values alone: every list of values of every item without the others, the names, the
     * groups and the texts of them alone.
     */
    fun ofFirstValues(task: SuggestionTask, m: Int): SuggestionTask {
        if (m >= task.valueCount) return task
        fun first(values: IntArray) = values.filter { it < m }.toIntArray()
        fun firstOf(lists: List<IntArray>) = lists.map(::first)
        // One array for every item of the same, as the task has it
        val allowed = IdentityHashMap<IntArray, IntArray>()
        val hinted = task.hints.indices.filter { task.hints[it].value < m }
        val among = IdentityHashMap<IntArray, IntArray>()
        return SuggestionTask(
            itemIds = task.itemIds,
            inputs = task.inputs,
            values = task.values.gather(IntArray(m) { it }),
            valueKeys = task.valueKeys.take(m),
            assigned = firstOf(task.assigned),
            confirmed = firstOf(task.confirmed),
            rejected = firstOf(task.rejected),
            context = task.context,
            contextKeys = task.contextKeys,
            allowed = task.allowed.map { a -> a?.let { allowed.getOrPut(it) { first(it) } } },
            groupings = task.groupings.map { ValueGrouping(it.name, it.groups, it.of.take(m)) },
            lackWeight = task.lackWeight,
            examples = firstOf(task.examples),
            hints =
                hinted.map { h ->
                    task.hints[h].let { NameHint(it.value, among.getOrPut(it.among) { first(it.among) }) }
                },
            hintTexts =
                if (hinted.isEmpty()) task.hintTexts.gather(IntArray(0))
                else task.hintTexts.gather(hinted.toIntArray()),
            drafts = firstOf(task.drafts),
            draftWeight = task.draftWeight,
            barred = firstOf(task.barred),
        )
    }

    /**
     * The [scores] of a task of its first values ([ofFirstValues]) as those of a task of [n] values: every value beyond
     * them given [least] — the suggester's least, of a value it knows nothing of.
     */
    fun beyond(scores: SuggestionScores, n: Int, least: Float): SuggestionScores =
        object : SuggestionScores {
            override fun of(rows: IntArray): List<FloatArray> =
                scores.of(rows).map { row ->
                    if (row.size >= n) row else FloatArray(n) { if (it < row.size) row[it] else least }
                }

            override fun chance(score: Float): Float = scores.chance(score)

            override val half: Float
                get() = scores.half

            override fun close() = scores.close()
        }
}
