package io.github.vlsergey.recommend4me.series

import kotlin.test.Test
import kotlin.test.assertEquals

/** The works of a normalized series in the order the sites' numbers give by pairs. */
class SeriesOrderTest {

    private val byId = compareBy<Long> { it }

    @Test
    fun `the sites' orders held together, the most of them where they differ, the unnumbered after`() {
        // One site numbers 1..3, another 3..4: the order of both
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            SeriesOrder.of(
                listOf(4L, 3L, 2L, 1L),
                listOf(mapOf(1L to 1, 2L to 2, 3L to 3), mapOf(3L to 1, 4L to 2)),
                byId,
            ),
        )
        // Two sites say 1 before 2, one says 2 before 1: the two win
        val numbered = listOf(mapOf(1L to 1, 2L to 2), mapOf(1L to 5, 2L to 7), mapOf(2L to 1, 1L to 2))
        assertEquals(listOf(1L, 2L), SeriesOrder.of(listOf(2L, 1L), numbered, byId))
        // A work no site numbers goes after, by the order of the rest
        assertEquals(
            listOf(2L, 1L, 9L, 10L),
            SeriesOrder.of(listOf(10L, 9L, 1L, 2L), listOf(mapOf(2L to 1, 1L to 2)), byId),
        )
    }
}
