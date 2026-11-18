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
        var cosine = 0f
        for (k in 0 until Letters.WIDTH) cosine += rows[a, k] * rows[b, k]
        return Lexical.of(cosine, lengths[a], lengths[b])
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
}
