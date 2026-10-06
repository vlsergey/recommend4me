package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairwiseScorerTest {

    @Test
    fun `the line learns the order of grades from pairs alone`() {
        val random = Random(7)
        val n = 120
        val d = 30
        val x = Matrix(n, d)
        val grades = IntArray(n) { i ->
            for (k in 0 until d) x.held[i * d + k] = random.nextFloat() * 2 - 1
            val s = x.held[i * d] + 0.5f * x.held[i * d + 1]
            when {
                s > 0.6f -> 5
                s > 0.2f -> 4
                s > -0.2f -> 3
                s > -0.6f -> 2
                else -> 1
            }
        }
        val task = RankingTask(x, grades, LongArray(n) { it.toLong() })
        val model = PairwiseScorer().fit(task, IntArray(n) { it }, 1.0) as LinearModel
        assertTrue(model.w[0] > 0 && model.w[1] > 0 && model.w[0] > model.w[1])

        val again = PairwiseScorer().unpack(model.pack())
        assertEquals(model.scores(x).toList(), again.scores(x).toList())
    }

    @Test
    fun `pairs are of different grades and different works`() {
        val grades = intArrayOf(1, 1, 1, 1, 2, 3)
        val works = longArrayOf(1, 2, 3, 4, 5, 5)
        val pairs = PairwiseScorer.pairs(grades, works, IntArray(grades.size) { it })
        // 3 over 1 (four), 2 over 1 (four); 3 and 2 are one work
        assertEquals(8, pairs.size)
        assertTrue((0 until pairs.size).all { grades[pairs.better[it]] > grades[pairs.worse[it]] })
    }
}
