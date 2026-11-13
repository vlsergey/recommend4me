package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** The user's word is of a snippet: a work is judged as the snippet it is shown with now. */
class SearchSnippetTest {

    @Test
    fun `a work shown with the snippet judged is judged so, shown with another of it not judged at all`() {
        val w1 = ItemKey("site", "w1")
        val w2 = ItemKey("site", "w2")
        val opening = SearchSnippet("annotation", "Dragons over the castle")
        val ending = SearchSnippet("part:c9", "The dragon sleeps at last")
        val judged =
            mapOf(
                w1 to mapOf(opening to SearchFit.FITS, ending to SearchFit.NOT),
                w2 to mapOf(opening to SearchFit.NOT),
            )
        assertEquals(mapOf(w1 to SearchFit.FITS), SearchSnippet.judgedShown(judged, mapOf(w1 to opening)))
        assertEquals(mapOf(w1 to SearchFit.NOT), SearchSnippet.judgedShown(judged, mapOf(w1 to ending)))
        // The same text of another field, or another text, is another snippet
        val elsewhere = SearchSnippet("notes", "Dragons over the castle")
        assertEquals(emptyMap(), SearchSnippet.judgedShown(judged, mapOf(w1 to elsewhere, w2 to ending)))
    }
}
