package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.vector.Vectors
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The closeness by meaning corrected by the user's judgments of snippets: a line's vector moved toward the snippets
 * judged to fit.
 */
class QueryShiftTest {

    private val dim = 8

    /** A unit vector of the [weights] of the first dimensions, the rest none. */
    private fun v(vararg weights: Double) = Vectors.unit(FloatArray(dim) { weights.getOrElse(it) { 0.0 }.toFloat() })

    private fun dot(x: FloatArray, y: FloatArray) = x.indices.sumOf { x[it].toDouble() * y[it] }

    @Test
    fun `a line as close to what fits as to what does not is moved toward what fits`() {
        // The encoder puts the line between the two kinds of snippets: what fits lies along the first dimension, what
        // does not along the second; every line a little apart along the third and the fourth
        fun line(n: Int) =
            SnippetLine(
                v(1.0, 1.0, 0.3 * n, 0.2),
                listOf(
                    v(1.0, 0.1, 0.1 * n) to SearchFit.FITS,
                    v(0.1, 1.0, 0.0, 0.1 * n) to SearchFit.NOT,
                    v(0.9, 0.0, 0.0, 0.3) to SearchFit.FITS,
                    v(0.6, 0.5, 0.1) to SearchFit.PARTLY,
                    v(0.0, 1.0, 0.2) to SearchFit.NOT,
                ),
            )
        val shift = assertNotNull(QueryShift.fit(listOf(line(1), line(2), line(3))))

        // A line never judged: what fits closer than what does not, as the encoder had them alike
        val q = v(1.0, 1.0, 0.5, 0.1)
        val fits = v(1.0, 0.0, 0.0, 0.1)
        val not = v(0.0, 1.0, 0.0, 0.1)
        val moved = shift.of(q)
        val before = dot(q, fits) - dot(q, not)
        val after = dot(moved, fits) - dot(moved, not)
        assertTrue(after > before + 0.01, "before $before, after $after")
        // A vector of another encoder is not moved
        assertTrue(shift.of(FloatArray(3) { 1f }).contentEquals(FloatArray(3) { 1f }))
    }

    @Test
    fun `two snippets of one work judged apart move the line apart between them, as two of two works would`() {
        // What the user said of each snippet is all the correction is learnt of: whose work it is tells nothing
        val line =
            SnippetLine(v(1.0, 1.0), listOf(v(1.0, 0.0, 0.2) to SearchFit.FITS, v(0.0, 1.0, 0.2) to SearchFit.NOT))
        val moved = assertNotNull(QueryShift.fit(listOf(line))).of(v(1.0, 1.0))
        assertTrue(dot(moved, v(1.0, 0.0, 0.2)) > dot(moved, v(0.0, 1.0, 0.2)))
    }

    @Test
    fun `no line with a snippet judged to answer it further than another teaches no correction`() {
        assertNull(QueryShift.fit(emptyList()))
        assertNull(
            QueryShift.fit(listOf(SnippetLine(v(1.0), listOf(v(1.0) to SearchFit.FITS, v(0.5) to SearchFit.FITS))))
        )
        // Nor a snippet of a vector of another encoder
        assertNull(
            QueryShift.fit(
                listOf(SnippetLine(v(1.0), listOf(v(1.0) to SearchFit.FITS, FloatArray(3) to SearchFit.NOT)))
            )
        )
    }
}
