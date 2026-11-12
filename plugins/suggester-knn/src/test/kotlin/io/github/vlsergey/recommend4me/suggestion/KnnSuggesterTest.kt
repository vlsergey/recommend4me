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
}
