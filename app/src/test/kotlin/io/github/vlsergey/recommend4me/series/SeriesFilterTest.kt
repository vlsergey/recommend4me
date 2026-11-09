package io.github.vlsergey.recommend4me.series

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which series the list shows by their sites and the user's word. */
class SeriesFilterTest {

    private fun series(key: String, vararg sources: String, said: Boolean = false) =
        NormalizedSeries(
            key,
            sources.associate { SiteSeriesRef(it, key) to emptyList() },
            if (said) setOf(1L) else emptySet(),
            setOf(1L),
            byHand = said,
        )

    private val all =
        listOf(
            series("a", "ficbook"),
            series("b", "ficbook", "author.today"),
            series("c", "author.today", said = true),
            series("d", said = true),
        )

    private fun let(filter: SeriesFilter) = all.filter(filter::lets).map { it.key }

    @Test
    fun `of every site with it, without it or whatever, and of the user's word`() {
        assertEquals(listOf("a", "b", "c", "d"), let(SeriesFilter()))
        assertEquals(listOf("a", "b"), let(SeriesFilter(with = listOf("ficbook"))))
        assertEquals(listOf("c", "d"), let(SeriesFilter(without = listOf("ficbook"))))
        assertEquals(listOf("b"), let(SeriesFilter(with = listOf("ficbook", "author.today"))))
        assertEquals(listOf("c", "d"), let(SeriesFilter(said = true)))
        assertEquals(listOf("a", "b"), let(SeriesFilter(said = false)))
        assertEquals(listOf("c"), let(SeriesFilter(with = listOf("author.today"), said = true)))
    }
}
