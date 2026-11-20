package io.github.vlsergey.recommend4me.part

import kotlin.test.Test
import kotlin.test.assertEquals

class WordCountTest {

    /** A hyphen or an apostrophe within a word is part of it, a dash standing alone no word. */
    @Test
    fun `words are counted as the sites count them`() {
        assertEquals(0, WordCount.of(""))
        assertEquals(3, WordCount.of("Раз, два — три."))
        assertEquals(2, WordCount.of("кто-то don't"))
        assertEquals(2, WordCount.of("конец- начало"))
        assertEquals(2, WordCount.of("- Да -\nнет"))
        assertEquals(2, WordCount.of("Глава 12"))
    }
}
