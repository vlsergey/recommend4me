package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnnScorerTest {

    private fun matrix(vararg rows: FloatArray): Matrix {
        val m = Matrix(rows.size, rows[0].size)
        rows.forEachIndexed { i, r -> m.held.put(r, 0, m.row(i), r.size) }
        return m
    }

    @Test
    fun `a work scores like the rated works it is alike`() {
        val rated = matrix(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0.9f, 0.1f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0.9f, 0.1f),
        )
        val task = RankingTask(rated, intArrayOf(5, 5, 1, 1), longArrayOf(1, 2, 3, 4), longArrayOf(1, 2, 3, 4))
        val model = KnnScorer().fit(task, intArrayOf(0, 1, 2, 3), 2.0)
        val scores = model.scores(matrix(floatArrayOf(1f, 0.05f, 0f), floatArrayOf(0.05f, 1f, 0f)))
        assertTrue(scores[0] > 4f && scores[1] < 2f, scores.toList().toString())

        val again = KnnScorer().unpack(model.pack())
        assertEquals(scores.toList(), again.scores(matrix(floatArrayOf(1f, 0.05f, 0f), floatArrayOf(0.05f, 1f, 0f))).toList())
    }
}
