package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whether the user's word that works are one series holds a series together, or the sites' series say it all. */
class SeriesJoinTest {

    private fun item(n: Int) = ItemKey("site", "i$n")

    /**
     * Of items 1..n, item n of work n; of [series], item to its series of the site; of [groups], group to its works.
     */
    private fun byHand(series: Map<Int, String>, groups: Map<Long, Set<Long>>): Boolean =
        SeriesJoin(
                series.mapKeys { item(it.key) },
                (1..WORKS).associate { item(it) to it.toLong() },
                groups,
            )
            .series()
            .single()
            .byHand

    @Test
    fun `a word on works one series of a site already says nothing it does not`() {
        assertEquals(false, byHand(mapOf(1 to "s", 2 to "s", 3 to "s"), mapOf(7L to setOf(1L, 2L))))
    }

    @Test
    fun `a word bringing a work of no series of a site holds it`() {
        assertEquals(true, byHand(mapOf(1 to "s", 2 to "s"), mapOf(7L to setOf(2L, 3L))))
    }

    @Test
    fun `a word joining two series of a site holds them together`() {
        assertEquals(true, byHand(mapOf(1 to "s", 2 to "t"), mapOf(7L to setOf(1L, 2L))))
    }

    @Test
    fun `two series of sites sharing a work are one without the word`() {
        // Work 2 has an item of both: the series one by it
        val series = mapOf(1 to "s", 2 to "s")
        val join =
            SeriesJoin(
                    series.mapKeys { item(it.key) } + (ItemKey("other", "x") to "t") + (ItemKey("other", "y") to "t"),
                    (1..WORKS).associate { item(it) to it.toLong() } +
                        (ItemKey("other", "x") to 2L) +
                        (ItemKey("other", "y") to 3L),
                    mapOf(7L to setOf(1L, 3L)),
                )
                .series()
                .single()
        assertEquals(false, join.byHand)
    }

    private companion object {
        const val WORKS = 3
    }
}
