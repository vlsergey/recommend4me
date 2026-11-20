package io.github.vlsergey.recommend4me.part

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CharClassesTest {

    /** Latin and Cyrillic letters apart by their bytes, the punctuation of one byte and of more, spaces, breaks. */
    @Test
    fun `the characters of a text counted by their classes`() {
        // letters 1 byte, letters 2, letters 3+, punctuation 1, punctuation 2+, spaces, breaks, other
        assertContentEquals(intArrayOf(5, 6, 0, 2, 0, 1, 1, 0), CharClasses.of("Привет, world!\n"))
        assertContentEquals(intArrayOf(0, 3, 0, 0, 3, 2, 2, 1), CharClasses.of("«Да» —\r\n\u00a0я+"))
        assertContentEquals(intArrayOf(1, 0, 2, 0, 0, 0, 0, 1), CharClasses.of("a漢字😀"))
    }
}
