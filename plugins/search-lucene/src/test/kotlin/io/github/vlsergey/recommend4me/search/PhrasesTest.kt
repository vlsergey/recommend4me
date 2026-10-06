package io.github.vlsergey.recommend4me.search

import kotlin.test.Test
import kotlin.test.assertEquals

class PhrasesTest {

    @Test
    fun `a text falls into its sentences and items, headings and numbers left out`() {
        val text = """
            v0.5
            - Added a new route with Anna. Fixed the gallery!
            * The beach scene now has three variants
            Bug fixes
        """.trimIndent()
        assertEquals(
            listOf("Added a new route with Anna.", "Fixed the gallery!", "The beach scene now has three variants"),
            Phrases.split(text),
        )
    }

    @Test
    fun `a long sentence is cut into pieces`() {
        val text = (1..100).joinToString(" ") { "word$it" }
        assertEquals(listOf(40, 40, 20), Phrases.split(text).map { it.split(' ').size })
    }
}
