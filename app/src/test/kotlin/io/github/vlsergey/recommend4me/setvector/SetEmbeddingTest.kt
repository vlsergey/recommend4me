package io.github.vlsergey.recommend4me.setvector

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetEmbeddingTest {

    /** Points spread 10 along the first axis, 3 along the second, a little along the rest. */
    private fun cloud(n: Int, d: Int = 8): List<FloatArray> {
        val random = Random(1)
        return List(n) { FloatArray(d) { k -> (random.nextDouble(-1.0, 1.0) * if (k == 0) 10.0 else if (k == 1) 3.0 else 0.1).toFloat() } }
    }

    @Test
    fun `the directions are the axes of the spread, the widest first`() {
        val embedding = SetEmbedding.fit(cloud(500), 2)
        assertTrue(abs(embedding.directions[0][0]) > 0.99f, "first ${embedding.directions[0].toList()}")
        assertTrue(abs(embedding.directions[1][1]) > 0.99f, "second ${embedding.directions[1].toList()}")
        assertTrue(embedding.scales[0] > embedding.scales[1])
    }

    @Test
    fun `a few members give as many directions as they span`() {
        val embedding = SetEmbedding.fit(cloud(3), 2)
        assertEquals(2, embedding.directions.size)
        assertEquals(2 * SetEmbedding.QUANTILES.size, embedding.of(cloud(5))!!.size)
    }
}
