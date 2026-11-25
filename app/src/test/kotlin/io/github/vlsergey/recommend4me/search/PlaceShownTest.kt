package io.github.vlsergey.recommend4me.search

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The place the pieces found as it is shown: from the beginning of the word of its first piece to the end of the word
 * of its last, every word of a piece marked whole; nothing of a text with fewer pieces than the place reaches.
 */
class PlaceShownTest {
    private val text = "A harbour of ships, and of a lantern lit on a reef."

    /** The pieces of the text: every word cut after its fourth letter, as an encoder cuts a word into pieces. */
    private val spans: List<IntRange?> =
        Regex("\\p{L}+")
            .findAll(text)
            .flatMap { m ->
                if (m.value.length <= 4) sequenceOf(m.range)
                else sequenceOf(m.range.first until m.range.first + 4, m.range.first + 4..m.range.last)
            }
            .toList()

    @Test
    fun `whole words shown and marked of pieces of words`() {
        // "ships" is the pieces 4 and 5, "lantern" 9 and 10, "lit" 11, "on" 12
        val shown = PlaceShown.of(SearchPlace("annotation", 4, 12, listOf(10, 11)), text, spans)!!
        assertEquals("annotation", shown.field)
        assertEquals(true, shown.byMeaning)
        assertEquals("ships, and of a lantern lit", shown.text)
        assertEquals(listOf("lantern", "lit"), shown.highlights.map { (a, b) -> shown.text.substring(a, b) })
    }

    @Test
    fun `nothing of a text of fewer pieces than the place`() {
        assertNull(PlaceShown.of(SearchPlace("annotation", 10, 40, listOf(11)), text, spans))
    }
}
