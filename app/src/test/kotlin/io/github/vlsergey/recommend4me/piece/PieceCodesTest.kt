package io.github.vlsergey.recommend4me.piece

import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pieces' vectors as they are kept and read: the leading directions of vectors, the codes of 2 bits a number read
 * back near the vector coded, the codebook kept and read alike; the best span of a text for a line the place where its
 * pieces stand.
 */
class PieceCodesTest {
    private val draw = Random(7)

    private fun unit(v: FloatArray) = PieceDirections.unit(v)

    private fun gaussian(n: Int) = FloatArray(n) { draw.nextGaussian().toFloat() }

    private fun cos(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }

    @Test
    fun `the leading direction of vectors spread along one is that one`() {
        val along = unit(gaussian(32))
        val vectors =
            List(2000) { k ->
                val t = (if (k % 2 == 0) 1 else -1) * (2 + draw.nextGaussian().toFloat())
                FloatArray(32) { along[it] * t + draw.nextGaussian().toFloat() * 0.1f }
            }
        val leading = PieceDirections.leading(vectors, 3)
        assertTrue(abs(cos(leading[0], along)) > 0.99, "the direction found")
        assertTrue(abs(cos(leading[1], leading[0])) < 1e-6, "the next one apart from it")
    }

    @Test
    fun `vectors of fewer directions than asked give nought for the rest`() {
        val vectors = List(100) { k -> FloatArray(8).also { it[k % 2] = 1f } }
        val leading = PieceDirections.leading(vectors, 4)
        assertTrue(leading.drop(2).all { d -> d.all { it == 0f } }, "nought, not NaN")
    }

    @Test
    fun `a code read back is near the vector coded, the codebook kept and read alike`() {
        val vectors = List(4000) { unit(gaussian(64)) }
        val book = PieceCodebook.learnt(vectors)
        assertEquals(16, book.bytes, "2 bits a number")
        val mean = vectors.take(500).map { cos(book.decode(book.code(it)), it) }.average()
        assertTrue(mean > 0.85, "the cosine of a vector read back: $mean")

        val again = PieceCodebook.unpack(book.pack(), book.width)
        val codes = book.codes(vectors.take(10))
        assertTrue((0 until 10).all { book.decode(codes, it * 16).contentEquals(again.decode(codes, it * 16)) })
    }

    @Test
    fun `the best span of a text is where the line's pieces stand, each marked`() {
        val words = List(40) { unit(gaussian(64)) }
        val book = PieceCodebook.learnt(List(4000) { unit(gaussian(64)) })
        val line = listOf(words[0], words[1], words[2])
        // A text of the other words, the line's three at 300, 302 and 305
        val text = MutableList(600) { words[3 + draw.nextInt(37)] }
        text[300] = words[0]
        text[302] = words[1]
        text[305] = words[2]
        val span = PieceSpans.best(line, book.codes(text), book)!!
        assertEquals(PieceSpans.SPAN * 3, span.to - span.from, "a span of 8 times the line")
        assertTrue(span.from <= 300 && span.to > 305, "the span ${span.from}..${span.to}")
        assertEquals(listOf(300, 302, 305), span.marked)
    }
}
