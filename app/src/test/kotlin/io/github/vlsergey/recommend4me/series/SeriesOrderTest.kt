package io.github.vlsergey.recommend4me.series

import kotlin.test.Test
import kotlin.test.assertEquals

/** The works of a normalized series in the order the sites' numbers give by pairs, the site of the most works first. */
class SeriesOrderTest {

    private val byId = compareBy<Long> { it }

    private fun site(vararg works: Long, volume: Double = 0.0) =
        SiteOrder(works.withIndex().associate { (k, w) -> w to k + 1 }, works.size, volume)

    @Test
    fun `the sites' orders held together, a work no site numbers after`() {
        // One site numbers 1..3, another 3..4: the order of both
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            SeriesOrder.of(listOf(4L, 3L, 2L, 1L), listOf(site(1, 2, 3), site(3, 4)), byId),
        )
        // A work no site numbers goes after, by the order of the rest
        assertEquals(listOf(2L, 1L, 9L, 10L), SeriesOrder.of(listOf(10L, 9L, 1L, 2L), listOf(site(2, 1)), byId))
        // A work the smaller site puts before one of the greater's: before it, after the work that stands nearer
        // the start of its list
        assertEquals(
            listOf(1L, 5L, 2L, 3L),
            SeriesOrder.of(listOf(1L, 2L, 3L, 5L), listOf(site(1, 2, 3), site(5, 2)), byId),
        )
    }

    @Test
    fun `where the sites differ, the site of the most works wins, of as many the one of the greater volume`() {
        // Three works say 1 before 2, two sites of two works say 2 before 1: the three win
        val three = site(1, 2, 3)
        assertEquals(
            listOf(1L, 2L, 3L),
            SeriesOrder.of(listOf(3L, 2L, 1L), listOf(site(2, 1), site(2, 1), three), byId),
        )
        // Two sites of as many works: the greater volume's order
        val small = site(2, 1, volume = 10.0)
        val large = site(1, 2, volume = 500.0)
        assertEquals(listOf(1L, 2L), SeriesOrder.of(listOf(2L, 1L), listOf(small, large), byId))
        assertEquals(listOf(2L, 1L), SeriesOrder.of(listOf(2L, 1L), listOf(site(2, 1, volume = 900.0), large), byId))
    }
}
