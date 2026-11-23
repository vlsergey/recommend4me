package io.github.vlsergey.recommend4me.duplicate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LettersTest {
    @Test
    fun `a string normalized — lower case, every run of what is no letter nor digit one space, trimmed`() {
        assertEquals("гарри поттер и кубок огня 4", Letters.normalized("  Гарри   Поттер!! и-Кубок «огня» #4 "))
        assertEquals("", Letters.normalized(" — !! "))
    }

    /** How alike the [a]-th and the [b]-th strings are, as the search compares them. */
    private fun lexical(texts: List<String?>, a: Int, b: Int): Lexical {
        val (rows, lengths) = Letters.rows(texts)
        val (whole, _) = Letters.wholeRows(texts)
        // Kept sparse in halves or whole in bytes, the same cosine but for a byte's thousandths
        assertEquals(whole.dot(a, whole, b), rows.dot(a, rows, b), BYTE_CLOSE)
        return Lexical.of(rows.dot(a, rows, b), lengths[a], lengths[b])
    }

    @Test
    fun `a string within another is all of its trigrams, the other not all of its own`() {
        val texts = listOf("Harry Potter", "harry potter and the goblet of fire", "The Silmarillion", null)
        val within = lexical(texts, 0, 1)
        assertTrue(within.within > 0.95f, "${within.within}")
        assertTrue(within.holding < 0.5f, "${within.holding}")
        assertTrue(lexical(texts, 0, 2).alike < 0.3f)
        // A string of none: no trigram, no share
        assertEquals(0f, lexical(texts, 3, 0).within)
        assertEquals(1f, lexical(texts, 0, 0).alike, 1e-2f)
    }

    private companion object {
        /** A cosine of two rows of a byte a number: off by a few thousandths. */
        const val BYTE_CLOSE = 1e-2f
    }
}
