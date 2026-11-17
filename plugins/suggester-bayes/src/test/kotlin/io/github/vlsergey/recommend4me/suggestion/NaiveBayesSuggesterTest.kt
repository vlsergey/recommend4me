package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * Of 60 works, the fandom telling the value 0 or 1 as in [task]; the work 4 alone has the value 2 too, and the user
     * confirmed its 0 and 2 and rejected its 1; rejected the 0 of the work 7, confirmed the 1 of the work 9.
     */
    private fun answeredTask(): SuggestionTask {
        val n = 60
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = emptyList(),
            values = Matrix(3, 4),
            valueKeys = listOf("v0", "v1", "v2"),
            assigned = List(n) { if (it == 4) intArrayOf(0, 2) else intArrayOf(it % 2) },
            confirmed = List(n) { if (it == 4) intArrayOf(0, 2) else if (it == 9) intArrayOf(1) else IntArray(0) },
            rejected = List(n) { if (it == 4) intArrayOf(1) else if (it == 7) intArrayOf(0) else IntArray(0) },
            context = List(n) { intArrayOf(it % 2) },
            contextKeys = listOf("fandom\u0001a", "fandom\u0001b"),
        )
    }

    /**
     * The chances of the work [i] of [task] by a model of every other work, of the settings [fitted] chose: the other
     * works fitted on, the work asked alone as one never seen.
     */
    private fun withoutItself(fitted: FittedSuggester, task: SuggestionTask, i: Int): FloatArray =
        asked(fitted, task, (0 until task.itemCount).filter { it != i }, task.context[i])

    /**
     * The chances of a work never fitted on, of the other values [context], by a model of the works [others] of [task]
     * of the settings [fitted] chose.
     */
    private fun asked(fitted: FittedSuggester, task: SuggestionTask, others: List<Int>, context: IntArray): FloatArray {
        val rest =
            SuggestionTask(
                itemIds = others.map { task.itemIds[it] },
                inputs = emptyList(),
                values = task.values,
                valueKeys = task.valueKeys,
                assigned = others.map { task.assigned[it] },
                confirmed = others.map { task.confirmed[it] },
                rejected = others.map { task.rejected[it] },
                context = others.map { task.context[it] },
                contextKeys = task.contextKeys,
            )
        val alone =
            SuggestionTask(
                itemIds = listOf("asked"),
                inputs = emptyList(),
                values = task.values,
                valueKeys = task.valueKeys,
                assigned = listOf(IntArray(0)),
                confirmed = listOf(IntArray(0)),
                rejected = listOf(IntArray(0)),
                context = listOf(context),
                contextKeys = task.contextKeys,
            )
        return assertNotNull(NaiveBayesSuggester().scoring(fitted.model(rest), alone)).of(intArrayOf(0))[0]
    }

    @Test
    fun `every value the user answered on is scored held out, by the counts of the other works alone`() {
        val task = answeredTask()
        val fitted = assertNotNull(NaiveBayesSuggester().fit(task, null))
        val held = assertNotNull(fitted.heldOut)
        // The answers, confirmed then rejected of each work, and no other pair
        assertEquals(listOf(4, 4, 4, 7, 9), held.items.toList())
        assertEquals(listOf(0, 2, 1, 0, 1), held.values.toList())
        // Each as a model of every other work scores it: on the scale of the scoring, a chance
        held.items.indices.forEach { p ->
            val expected = withoutItself(fitted, task, held.items[p])[held.values[p]]
            assertEquals(expected, held.scores[p], 1e-5f, "work ${held.items[p]}, value ${held.values[p]}")
        }
        // The value 2 no other work has: the work's own answer is no witness of it — lower than of a work of its
        // fandom never fitted on, whose counts have the work 4's value 2 in them
        val seen = asked(fitted, task, (0 until task.itemCount).toList(), task.context[4])
        assertTrue(held.scores[1] < 0.2f && held.scores[1] < seen[2], "${held.scores[1]} against ${seen[2]}")
        assertTrue(held.scores[0] > 0.8f && held.scores[2] < 0.2f, held.scores.toList().toString())
        // A state unpacked holds nothing out
        assertNull(assertNotNull(NaiveBayesSuggester().unpack(fitted.pack(), task)).heldOut)
    }
}
