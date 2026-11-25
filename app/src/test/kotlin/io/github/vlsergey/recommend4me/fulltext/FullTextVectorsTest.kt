package io.github.vlsergey.recommend4me.fulltext

import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The full-text vectors of the windows as they are kept and read: every window's characters read back, a bit a number;
 * the line's own window the first by its score, and the scores in the order of the cosines of windows near and far.
 */
class FullTextVectorsTest {
    private val draw = Random(5)

    private fun unit(v: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun gaussian(n: Int) = FloatArray(n) { draw.nextGaussian().toFloat() }

    @Test
    fun `the windows read back, the nearer to the line the higher`() {
        val dim = 100
        val centre = FloatArray(dim) { 0.05f * draw.nextGaussian().toFloat() }
        val line = unit(gaussian(dim))
        // The line's own vector, one near it, one farther, one apart
        val near = unit(FloatArray(dim) { line[it] + 0.5f * draw.nextGaussian().toFloat() / 10 })
        val farther = unit(FloatArray(dim) { line[it] + 2f * draw.nextGaussian().toFloat() / 10 })
        val apart = unit(gaussian(dim))
        val windows = listOf(line, near, farther, apart).mapIndexed { k, v -> TextWindow(k * 10, k * 10 + 7, v) }
        val packed = FullTextVectors.pack(windows, centre)
        assertEquals(
            FullTextVectors.HEAD + 4 * (FullTextVectors.WINDOW_HEAD + 13),
            packed.size,
            "13 bytes of 100 signs",
        )

        val query = FullTextVectors.query(line, centre)
        val read = ArrayList<Triple<Int, Int, Float>>()
        FullTextVectors.forEachWindow(packed) { from, to, at -> read += Triple(from, to, query.score(packed, at)) }
        assertEquals(listOf(0 to 7, 10 to 17, 20 to 27, 30 to 37), read.map { it.first to it.second })
        val scores = read.map { it.third }
        assertTrue(scores == scores.sortedDescending(), "$scores")
    }
}
