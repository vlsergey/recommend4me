package io.github.vlsergey.recommend4me.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A work's chances of a facet in an array read as a map of them by value, alike to one made as a table. */
class FewChancesTest {

    private val chances =
        listOf("a" to 0.9, "b" to 0.2, "c" to 0.6).map { (k, c) -> Chance("w", "tags", k, had = k == "a", chance = c) }

    @Test
    fun `read by value as a table of the same chances`() {
        val few = FewChances(chances.toTypedArray())
        val table = chances.associateBy { it.key }
        assertEquals(table, few)
        assertEquals(few, table)
        assertEquals(table.hashCode(), few.hashCode())
        assertEquals(3, few.size)
        assertEquals(chances[2], few["c"])
        assertNull(few["d"])
        assertTrue("b" in few)
        assertFalse("d" in few)
        assertEquals(setOf("a", "b", "c"), few.keys)
        assertEquals(chances, few.values.toList())
        assertEquals(ModelValues.given(table), ModelValues.given(few))
    }

    @Test
    fun `none is an empty map`() {
        val few = FewChances(emptyArray())
        assertTrue(few.isEmpty())
        assertEquals(emptyMap<String, Chance>(), few)
    }
}
