package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NaiveBayesSuggesterTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(NaiveBayesSuggester().scoring(fitted.model(task), task))

    /** Of 60 works, those of the fandom "a" have the value 0, those of "b" the value 1 — the fandom tells it alone. */
    private fun task(): SuggestionTask {
        val n = 60
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = emptyList(),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { intArrayOf(it % 2) },
            contextKeys = listOf("fandom\u0001a", "fandom\u0001b"),
        )
    }

    @Test
    fun `the values of the other facets tell a work's value, judged on works held out`() {
        val task = task()
        val fitted = assertNotNull(NaiveBayesSuggester().fit(task, null))
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 9 && report.fromScratch)
        // Held out, it is far better than a coin: the loss of a chance of one half is ln 2
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        // Scored by every other work's counts, its own taken out
        val chances = scored(fitted, task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
        // Packed: the settings alone, to go on from; the counts of the task fitted on are the model
        val unpacked = assertNotNull(NaiveBayesSuggester().unpack(fitted.pack(), task))
        assertTrue(scored(unpacked, task).of(intArrayOf(0))[0][0] > 0.8f)
    }

    @Test
    fun `a work read alone later is scored by the counts kept, of its own other values, its own pairs taken out`() {
        val task = task()
        val suggester = NaiveBayesSuggester()
        val model = assertNotNull(suggester.fit(task, null)).model(task)
        // A page of two works: one never fitted on, of the fandom "b", and one fitted on — its own pairs no witness —
        // their other values numbered their own way, one the model never saw among them
        val alone =
            SuggestionTask(
                itemIds = listOf("new", "w0"),
                inputs = emptyList(),
                values = Matrix(2, 0),
                valueKeys = listOf("v0", "v1"),
                assigned = listOf(IntArray(0), intArrayOf(0)),
                confirmed = List(2) { IntArray(0) },
                rejected = List(2) { IntArray(0) },
                context = listOf(intArrayOf(0, 1), intArrayOf(2)),
                contextKeys = listOf("fandom\u0001b", "author\u0001never seen", "fandom\u0001a"),
            )
        val chances = assertNotNull(suggester.scoring(model, alone)).of(intArrayOf(0, 1))
        assertTrue(chances[0][1] > 0.8f && chances[0][0] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][0] > 0.8f, chances[1].toList().toString())
        // Of other values than the model's: not scored
        val other =
            SuggestionTask(
                alone.itemIds,
                emptyList(),
                Matrix(3, 0),
                listOf("v0", "v1", "v2"),
                alone.assigned,
                alone.confirmed,
                alone.rejected,
                alone.context,
                alone.contextKeys,
            )
        assertTrue(suggester.scoring(model, other) == null)
    }
}
