package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnnSuggesterTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(KnnSuggester().scoring(fitted.model(task), task))

    /** Of 80 works, the texts of those of the value 0 lie near one direction, of the value 1 near another. */
    private fun task(): SuggestionTask {
        val n = 80
        val d = 16
        val random = Random(11)
        val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
        val texts = Matrix(n, d)
        repeat(n) { i ->
            val v = FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() }
            texts.held.put(v, 0, texts.row(i), d)
        }
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(n) { true })),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    @Test
    fun `a work has the values of the works nearest it by its texts, judged on works held out`() {
        val task = task()
        // No card: the processor finds the nearest
        val fitted = assertNotNull(KnnSuggester().fit(task, null))
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 4 && report.fromScratch)
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        val chances = scored(fitted, task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
    }

    @Test
    fun `a work read alone later is scored by its nearest among the works kept, of its own texts, never by itself`() {
        val task = task()
        val suggester = KnnSuggester()
        val model = assertNotNull(suggester.fit(task, null)).model(task)
        // A page of two works: one never fitted on, its text the text of a work of the value 1; and the work w0 itself,
        // of the value 0, its text now that of a work of the value 1 — scored by the others, not by its own old self
        val block = task.inputs.single()
        val texts = Matrix(2, block.dim)
        val row = FloatArray(block.dim)
        block.vectors.take(block.rows.indexOf(1), row)
        texts.held.put(row, 0, texts.row(0), block.dim)
        texts.held.put(row, 0, texts.row(1), block.dim)
        val alone =
            SuggestionTask(
                itemIds = listOf("new", "w0"),
                inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(2) { true })),
                values = Matrix(2, 0),
                valueKeys = listOf("v0", "v1"),
                assigned = List(2) { IntArray(0) },
                confirmed = List(2) { IntArray(0) },
                rejected = List(2) { IntArray(0) },
                context = List(2) { IntArray(0) },
                contextKeys = emptyList(),
            )
        val chances = assertNotNull(suggester.scoring(model, alone)).of(intArrayOf(0, 1))
        chances.forEach { assertTrue(it[1] > 0.8f && it[0] < 0.2f, it.toList().toString()) }
        // Every value's share of the works kept, told without a work read
        val weights = assertNotNull(suggester.weights(model))
        assertEquals(0.5, weights[0], 0.01)
        // Of a block of another width: not scored
        val other =
            SuggestionTask(
                alone.itemIds,
                listOf(InputBlock.of("text:annotation", Matrix(2, block.dim + 1), BooleanArray(2) { true })),
                alone.values,
                alone.valueKeys,
                alone.assigned,
                alone.confirmed,
                alone.rejected,
                alone.context,
                alone.contextKeys,
            )
        assertNull(suggester.scoring(model, other))
    }

    /** The texts of 12 works, those of the even near one direction, of the odd near another. */
    private fun answeredTexts(): List<FloatArray> {
        val d = 16
        val random = Random(17)
        val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
        return List(12) { i -> FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() } }
    }

    /**
     * The works [rows] of the [texts], their values those of their texts' direction, the work 4 alone the value 2 too:
     * the user confirmed its 0 and 2 and rejected its 1, rejected the 0 of the work 7 and confirmed the 1 of the
     * work 9. [ids]: of every work of [rows].
     */
    private fun answeredTask(texts: List<FloatArray>, rows: List<Int>, ids: List<String>): SuggestionTask {
        val d = texts.first().size
        val matrix = Matrix(rows.size, d)
        rows.forEachIndexed { r, i -> matrix.held.put(texts[i], 0, matrix.row(r), d) }
        return SuggestionTask(
            itemIds = ids,
            inputs = listOf(InputBlock.of("text:annotation", matrix, BooleanArray(rows.size) { true })),
            values = Matrix(3, 4),
            valueKeys = listOf("v0", "v1", "v2"),
            assigned = rows.map { if (it == 4) intArrayOf(0, 2) else intArrayOf(it % 2) },
            confirmed = rows.map { if (it == 4) intArrayOf(0, 2) else if (it == 9) intArrayOf(1) else IntArray(0) },
            rejected = rows.map { if (it == 4) intArrayOf(1) else if (it == 7) intArrayOf(0) else IntArray(0) },
            context = rows.map { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    /**
     * The chances of a work never fitted on, of the text of the work [i], by a model of the works [others] of the k
     * [fitted] chose.
     */
    private fun asked(fitted: FittedSuggester, texts: List<FloatArray>, others: List<Int>, i: Int): FloatArray {
        val model = fitted.model(answeredTask(texts, others, others.map { "w$it" }))
        val alone = answeredTask(texts, listOf(i), listOf("asked"))
        return assertNotNull(KnnSuggester().scoring(model, alone)).of(intArrayOf(0))[0]
    }

    @Test
    fun `every value the user answered on is scored held out, by the nearest of the other works alone`() {
        val texts = answeredTexts()
        val all = texts.indices.toList()
        val task = answeredTask(texts, all, all.map { "w$it" })
        val fitted = assertNotNull(KnnSuggester().fit(task, null))
        val held = assertNotNull(fitted.heldOut)
        // The answers, confirmed then rejected of each work, and no other pair
        assertEquals(listOf(4, 4, 4, 7, 9), held.items.toList())
        assertEquals(listOf(0, 2, 1, 0, 1), held.values.toList())
        // Each as a model of every other work scores it — on the scale of the scoring, a chance — within the half
        // precision the model keeps the works in
        held.items.indices.forEach { p ->
            val i = held.items[p]
            val expected = asked(fitted, texts, all.filter { it != i }, i)[held.values[p]]
            assertEquals(expected, held.scores[p], 2e-3f, "work $i, value ${held.values[p]}")
        }
        // The value 2 no other work has: the work's own answer is no witness of it — lower than of a work of its text
        // never fitted on, whose neighbours and shares have the work 4 among them
        val seen = asked(fitted, texts, all, 4)
        assertTrue(held.scores[1] < 0.05f && held.scores[1] < seen[2], "${held.scores[1]} against ${seen[2]}")
        // Of a few works every one is a neighbour: the value of its direction likelier than the other's still
        assertTrue(held.scores[0] > 0.5f && held.scores[2] < 0.5f, held.scores.toList().toString())
        // A state unpacked holds nothing out
        assertNull(assertNotNull(KnnSuggester().unpack(fitted.pack(), task)).heldOut)
    }
}
