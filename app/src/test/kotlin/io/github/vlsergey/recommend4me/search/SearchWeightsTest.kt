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
                JudgedLine(
                    "a",
                    listOf(
                        work(5.0, 3.0) to SearchFit.FITS,
                        work(5.2, 0.5) to SearchFit.NOT,
                        work(4.9, 2.5) to SearchFit.FITS,
                    ),
                ),
                JudgedLine("b", listOf(work(-1.0, 1.5) to SearchFit.FITS, work(-0.9, 0.1) to SearchFit.NOT)),
                // Every work judged one way: no pair, nothing of an order
                JudgedLine("c", listOf(work(9.0, 9.0) to SearchFit.NOT)),
            )
        val weights = assertNotNull(SearchWeights.fit(lines))
        val words = weights.features.getValue("words").weight
        val reranker = weights.features.getValue("reranker").weight
        // The reranker the least a weight may be: half its equal share, a quarter of the two, never nought
        assertTrue(reranker > 0 && words >= 3 * reranker - 1e-9, "words $words, reranker $reranker")

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
    fun `no feature weighs nought nor against, whatever the lines judged say`() {
        // The works found by the line's words judged not to answer it, those found by meaning alone judged to
        val lines =
            listOf(
                JudgedLine(
                    "a",
                    listOf(
                        mapOf("meaning" to 0.9, "words" to 0.0) to SearchFit.FITS,
                        mapOf("meaning" to 0.2, "words" to 3.0) to SearchFit.NOT,
                        mapOf("meaning" to 0.8, "words" to 0.0) to SearchFit.FITS,
                    ),
                )
            )
        val weights = assertNotNull(SearchWeights.fit(lines))
        val words = weights.features.getValue("words").weight
        val meaning = weights.features.getValue("meaning").weight
        assertTrue(words > 0 && words >= (words + meaning) / 4 - 1e-9, "words $words, meaning $meaning")
        // A work of more of the line's words stands higher than one of none, by meaning alike
        assertTrue(weights.score(mapOf("meaning" to 0.5, "words" to 3.0)) > weights.score(mapOf("meaning" to 0.5)))
        // Every feature judged against: none of them nought, the weights summing to how many they are all the same
        val against =
            assertNotNull(
                SearchWeights.fit(
                    listOf(
                        JudgedLine(
                            "b",
                            listOf(
                                mapOf("meaning" to 0.1, "words" to 0.0) to SearchFit.FITS,
                                mapOf("meaning" to 0.9, "words" to 3.0) to SearchFit.NOT,
                            ),
                        )
                    )
                )
            )
        val all = against.features.values.map { it.weight }
        assertEquals(2.0, all.sum(), 1e-9)
        assertTrue(all.all { it >= 0.5 - 1e-9 }, "$all")
    }

    @Test
    fun `the first of every way of finding stands at the top, whatever the weights`() {
        val weights =
            SearchWeights(mapOf("meaning" to FeatureWeight(10.0, 0.0, 1.0), "words" to FeatureWeight(0.01, 0.0, 1.0)))
        val keys = (1..5).map { ItemKey("s", "$it") }
        val found =
            SearchResult(
                "enf",
                keys,
                null,
                features =
                    mapOf(
                        keys[0] to mapOf("meaning" to 0.9),
                        keys[1] to mapOf("meaning" to 0.8),
                        keys[2] to mapOf("meaning" to 0.7),
                        keys[3] to mapOf("meaning" to 0.1, "words" to 1.0),
                        keys[4] to mapOf("meaning" to 0.1, "words" to 5.0),
                        // A way of finding no weight was learnt of: its first at the top too
                    ) + mapOf(ItemKey("s", "6") to mapOf("name" to 2.0)),
            )
        val withName = SearchResult(found.line, keys + ItemKey("s", "6"), null, features = found.features)
        val order = weights.ordered(withName).keys
        // The first by meaning, by words, by the name, in the order of the weights; then the rest by the weights
        assertEquals(
            listOf(keys[0], keys[4], ItemKey("s", "6"), keys[1], keys[2], keys[3]),
            order,
        )
    }

    @Test
    fun `a line of works answering it whole and in part teaches the order of them`() {
        val weights =
            assertNotNull(
                SearchWeights.fit(
                    listOf(
                        JudgedLine("a", listOf(work(1.0, 3.0) to SearchFit.FITS, work(1.0, 1.0) to SearchFit.PARTLY))
                    )
                )
            )
        assertTrue(weights.features.getValue("words").weight > 0)
    }

    @Test
    fun `the works judged in the order of how far they answer a line, the first ones the most`() {
        val fits = ItemKey("s", "1")
        val partly = ItemKey("s", "2")
        val not = ItemKey("s", "3")
        val judged = mapOf(fits to SearchFit.FITS, partly to SearchFit.PARTLY, not to SearchFit.NOT)
        // The best order, and the works not judged left out
        assertEquals(1.0, SearchQuality.ndcg(listOf(ItemKey("s", "9"), fits, partly, not), judged, 10))
        val swapped = assertNotNull(SearchQuality.ndcg(listOf(partly, fits, not), judged, 10))
        val last = assertNotNull(SearchQuality.ndcg(listOf(not, partly, fits), judged, 10))
        assertTrue(swapped < 1.0 && last < swapped, "swapped $swapped, last $last")
        // A line nothing answers has none
        assertNull(SearchQuality.ndcg(listOf(not), mapOf(not to SearchFit.NOT), 10))
    }

    @Test
    fun `no line with a work judged to answer it and one judged not to teaches no order`() {
        assertNull(SearchWeights.fit(emptyList()))
        assertNull(
            SearchWeights.fit(
                listOf(JudgedLine("a", listOf(work(1.0, 1.0) to SearchFit.FITS, work(2.0, 0.0) to SearchFit.FITS)))
            )
        )
    }
}
