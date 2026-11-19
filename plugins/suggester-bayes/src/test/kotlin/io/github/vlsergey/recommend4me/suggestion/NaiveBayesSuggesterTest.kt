package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaiveBayesSuggesterTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(NaiveBayesSuggester().scoring(fitted.model(task), task))

    /** The fitting of [task] handed over whole, as one portion. */
    private fun fit(task: SuggestionTask) = assertNotNull(NaiveBayesSuggester().fit(TaskPortions.of(task)))

    /**
     * The items of [task] in portions of [size], as the application hands a source of many works over; [walks]: how
     * often they were walked.
     */
    private fun portionsOf(task: SuggestionTask, size: Int, walks: IntArray = IntArray(1)): TaskPortions =
        object : TaskPortions {
            override val values = task.ofItems(IntArray(0))
            override val items = task.itemCount

            override fun forEach(action: (SuggestionTask) -> Unit) {
                walks[0]++
                for (from in 0 until task.itemCount step size) {
                    action(task.ofItems(IntArray(minOf(size, task.itemCount - from)) { from + it }))
                }
            }
        }

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
        val fitted = fit(task)
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 9 && report.fromScratch)
        // Held out, it is far better than a coin: the loss of a chance of one half is ln 2
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        // Scored by every other work's counts, its own taken out
        val chances = scored(fitted, task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
        // Packed: the counts and the settings, the model made of them alone; of other values, fitted again
        val unpacked = assertNotNull(NaiveBayesSuggester().unpack(fitted.pack(), task.ofItems(IntArray(0))))
        assertContentEquals(scored(fitted, task).of(intArrayOf(0))[0], scored(unpacked, task).of(intArrayOf(0))[0])
        assertNull(NaiveBayesSuggester().unpack(fitted.pack(), answeredTask().ofItems(IntArray(0))))
    }

    @Test
    fun `a work read alone later is scored by the counts kept, of its own other values, its own pairs taken out`() {
        val task = task()
        val suggester = NaiveBayesSuggester()
        val model = fit(task).model(task.ofItems(IntArray(0)))
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
        // Of a value beyond the model's, after them: scored a chance of none, the model's as they were (NewValues)
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
        val beyond = assertNotNull(suggester.scoring(model, other)).of(intArrayOf(0, 1))
        assertEquals(listOf(chances[0].toList() + 0f, chances[1].toList() + 0f), beyond.map { it.toList() })
        // Of fewer values than the model's: not scored
        assertTrue(suggester.scoring(model, NewValues.ofFirstValues(alone, 1)) == null)
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
     * The chances of a work of the other values [context] by the counts of the works [others] of [task] alone, of the
     * settings [fitted] chose: the work counted in none of them.
     */
    private fun byOthers(fitted: FittedSuggester, task: SuggestionTask, others: List<Int>, context: IntArray) =
        assertNotNull(fitted.report).chosen.let { chosen ->
            val counts = BayesFolds.of(TaskPortions.of(task.ofItems(others.toIntArray()))) { false }.total
            counts.chances(context, null, chosen.getValue("α").toDouble(), chosen.getValue("s").toDouble())
        }

    @Test
    fun `every value the user answered on is scored held out, by the counts of the other works alone`() {
        val task = answeredTask()
        val fitted = fit(task)
        val held = assertNotNull(fitted.heldOut)
        // The answers by the works' ids, confirmed then rejected of each work, and no other pair
        assertEquals(listOf("w4", "w4", "w4", "w7", "w9"), held.items)
        assertEquals(listOf(0, 2, 1, 0, 1), held.values.toList())
        val model = fitted.model(task.ofItems(IntArray(0)))
        held.items.indices.forEach { p ->
            val i = held.items[p].removePrefix("w").toInt()
            // As the counts of every other work score it
            val expected = byOthers(fitted, task, (0 until task.itemCount).filter { it != i }, task.context[i])
            assertEquals(expected[held.values[p]], held.scores[p], 1e-5f, "work $i, value ${held.values[p]}")
            // As the model kept scores it, its own pairs taken out
            val kept =
                assertNotNull(NaiveBayesSuggester().scoring(model, task.ofItems(intArrayOf(i)))).of(intArrayOf(0))
            assertEquals(kept[0][held.values[p]], held.scores[p], 1e-6f, "work $i, value ${held.values[p]} kept")
        }
        // The value 2 no other work has: the work's own answer is no witness of it — lower than of a work of its
        // fandom counted in none, by the counts that have the work 4's value 2 in them
        val seen = byOthers(fitted, task, (0 until task.itemCount).toList(), task.context[4])
        assertTrue(held.scores[1] < 0.2f && held.scores[1] < seen[2], "${held.scores[1]} against ${seen[2]}")
        assertTrue(held.scores[0] > 0.8f && held.scores[2] < 0.2f, held.scores.toList().toString())
        // A state unpacked holds nothing out
        assertNull(assertNotNull(NaiveBayesSuggester().unpack(fitted.pack(), task.ofItems(IntArray(0)))).heldOut)
    }

    @Test
    fun `the works in several portions fit what they fit as one, in two walks`() {
        val task = answeredTask()
        val whole = fit(task)
        val walks = IntArray(1)
        val split = assertNotNull(NaiveBayesSuggester().fit(portionsOf(task, 7, walks)))
        assertEquals(2, walks[0])
        val a = assertNotNull(whole.report)
        val b = assertNotNull(split.report)
        assertEquals(a.chosen, b.chosen)
        assertEquals(a.loss, b.loss, 1e-12)
        a.tried.zip(b.tried).forEach { (x, y) -> assertEquals(x.loss, y.loss, 1e-12) }
        val heldA = assertNotNull(whole.heldOut)
        val heldB = assertNotNull(split.heldOut)
        assertEquals(heldA.items, heldB.items)
        assertContentEquals(heldA.values, heldB.values)
        heldA.scores.indices.forEach { assertEquals(heldA.scores[it], heldB.scores[it], 1e-6f) }
        // The same counts kept: every work scored alike
        val side = task.ofItems(IntArray(0))
        val rows = IntArray(task.itemCount) { it }
        val byWhole = scored(whole, task).of(rows)
        val bySplit = assertNotNull(NaiveBayesSuggester().scoring(split.model(side), task)).of(rows)
        rows.forEach { i -> byWhole[i].indices.forEach { v -> assertEquals(byWhole[i][v], bySplit[i][v], 1e-6f) } }
    }

    @Test
    fun `no work with a value, nothing fitted`() {
        val task = task()
        val bare =
            SuggestionTask(
                task.itemIds,
                emptyList(),
                task.values,
                task.valueKeys,
                List(task.itemCount) { IntArray(0) },
                task.confirmed,
                task.rejected,
                task.context,
                task.contextKeys,
            )
        assertNull(NaiveBayesSuggester().fit(portionsOf(bare, 7)))
    }
}
