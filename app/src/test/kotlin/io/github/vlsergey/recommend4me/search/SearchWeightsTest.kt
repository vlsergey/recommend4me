package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order of what a search finds learnt of the user's judgments: by pairs within a line, every line alike. */
class SearchWeightsTest {

    private fun work(reranker: Double, words: Double) = mapOf("reranker" to reranker, "words" to words)

    @Test
    fun `the feature that tells the works judged apart weighs, the one that does not weighs little`() {
        // The reranker puts the fitting and the not fitting alike; the words found tell them apart — on the scale of
        // each line, one far above the other
        val lines =
            listOf(
                JudgedLine("a", listOf(work(5.0, 3.0) to true, work(5.2, 0.5) to false, work(4.9, 2.5) to true)),
                JudgedLine("b", listOf(work(-1.0, 1.5) to true, work(-0.9, 0.1) to false)),
                // Every work judged one way: no pair, nothing of an order
                JudgedLine("c", listOf(work(9.0, 9.0) to false)),
            )
        val weights = assertNotNull(SearchWeights.fit(lines))
        val words = weights.features.getValue("words").weight
        val reranker = weights.features.getValue("reranker").weight
        assertTrue(words > 0.5 && words > 3 * kotlin.math.abs(reranker), "words $words, reranker $reranker")

        // A line never judged put in the order: the works its words found first, whatever the reranker said
        val found =
            SearchResult(
                "d",
                listOf(ItemKey("s", "1"), ItemKey("s", "2")),
                null,
                features = mapOf(ItemKey("s", "1") to work(6.0, 0.2), ItemKey("s", "2") to work(5.0, 2.8)),
            )
        assertEquals(listOf(ItemKey("s", "2"), ItemKey("s", "1")), weights.ordered(found).keys)
        // A feature a work lacks is 0 of it
        assertTrue(weights.score(mapOf("words" to 2.0)) > weights.score(emptyMap()))
    }

    @Test
    fun `no line with a work judged to answer it and one judged not to teaches no order`() {
        assertNull(SearchWeights.fit(emptyList()))
        assertNull(SearchWeights.fit(listOf(JudgedLine("a", listOf(work(1.0, 1.0) to true, work(2.0, 0.0) to true)))))
    }
}
