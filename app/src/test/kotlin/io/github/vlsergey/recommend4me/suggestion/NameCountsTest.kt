package io.github.vlsergey.recommend4me.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A work's counts of names by the values' places: read as the table of them a map makes. */
class NameCountsTest {

    @Test
    fun `the counts of the values with a place, by their places`() {
        val index = mapOf("harry" to 4, "ron" to 0, "hermione" to 9)
        val counts = NameCounts.of(mapOf("hermione" to 3, "harry" to 7, "draco" to 1, "ron" to 2), index)
        val table = mapOf(9 to 3, 4 to 7, 0 to 2)
        assertEquals<Map<Int, Int>>(table, counts)
        assertEquals(table.hashCode(), counts.hashCode())
        assertEquals(7, counts[4])
        assertNull(counts[5])
        assertTrue(counts.containsKey(0))
        assertFalse(counts.containsKey(1))
        assertEquals(listOf(0, 4, 9), counts.keys.toList())
        assertEquals(0, NameCounts.of(emptyMap(), index).size)
    }
}
