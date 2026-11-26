package io.github.vlsergey.recommend4me.fulltext

import java.time.Instant
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The full-text vectors of the windows as they are kept and read: every window's characters read back, a bit a number
 * after a random rotation; the line's own window the first by its score, and the scores in the order of the cosines of
 * windows near and far; a value of another format, or cut short, not read.
 */
class FullTextVectorsTest {
    private val draw = Random(5)

    private fun unit(v: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun gaussian(n: Int) = FloatArray(n) { draw.nextGaussian().toFloat() }

    @Test
    fun `a vector is turned as the rotation's rows multiply it, within the last digits`() {
        listOf(100, 1024).forEach { dim ->
            val centre = FullTextCentre("c", gaussian(dim), FullTextCentre.rotation(dim, draw), Instant.now(), 0, 0)
            val v = unit(gaussian(dim))
            val got = centre.turned(v)
            centre.rotation.forEachIndexed { k, row ->
                val want = row.indices.sumOf { row[it].toDouble() * (v[it] - centre.centre[it]) }
                assertEquals(want, got[k].toDouble(), 1e-4, "$dim numbers, row $k")
            }
        }
    }

    @Test
    fun `the windows read back, the nearer to the line the higher`() {
        val dim = 100
        val centre =
            FullTextCentre(
                "c",
                FloatArray(dim) { 0.05f * draw.nextGaussian().toFloat() },
                FullTextCentre.rotation(dim, draw),
                Instant.now(),
                0,
                0,
            )
        val line = unit(gaussian(dim))
        // The line's own vector, one near it, one farther, one apart
        val near = unit(FloatArray(dim) { line[it] + 0.5f * draw.nextGaussian().toFloat() / 10 })
        val farther = unit(FloatArray(dim) { line[it] + 2f * draw.nextGaussian().toFloat() / 10 })
        val apart = unit(gaussian(dim))
        val windows = listOf(line, near, farther, apart).mapIndexed { k, v -> TextWindow(k * 10, k * 10 + 7, v) }
        val packed = FullTextVectors.pack(windows, centre)
        assertEquals(
            FullTextVectors.HEAD + 4 * (FullTextVectors.WINDOW_HEAD + 2 * Long.SIZE_BYTES),
            packed.size,
            "two words of 100 bits",
        )

        val query = FullTextVectors.query(line, centre)
        assertTrue(FullTextVectors.readable(packed, query), "of the query's format")
        // Of the format before, a byte of signs a number: 13 bytes a window of 100 numbers, not words of 64 bits
        val before = java.nio.ByteBuffer.allocate(FullTextVectors.HEAD + 4 * (FullTextVectors.WINDOW_HEAD + 13))
        before.putInt(13).putInt(4)
        assertFalse(FullTextVectors.readable(before.array(), query), "of the format before")
        assertFalse(FullTextVectors.readable(packed.copyOf(packed.size - 1), query), "cut short")
        val read = ArrayList<Triple<Int, Int, Float>>()
        FullTextVectors.forEachWindow(packed) { from, to, at -> read += Triple(from, to, query.score(packed, at)) }
        assertEquals(listOf(0 to 7, 10 to 17, 20 to 27, 30 to 37), read.map { it.first to it.second })
        val scores = read.map { it.third }
        assertTrue(scores == scores.sortedDescending(), "$scores")
    }
}
