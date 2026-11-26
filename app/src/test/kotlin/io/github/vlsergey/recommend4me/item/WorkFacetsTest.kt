package io.github.vlsergey.recommend4me.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every work's values read row by row into arrays: read as the tables of them the rows make. */
class WorkFacetsTest {

    private val rows =
        listOf(
            Triple(1L, "tags", "a"),
            Triple(2L, "fandom", "hp"),
            Triple(1L, "fandom", "hp"),
            Triple(1L, "tags", "b"),
            Triple(2L, "tags", "a"),
            Triple(3L, "rating", "pg-13"),
        )

    @Test
    fun `every work's facets as the rows give them, in the order read`() {
        val reading = WorkFacets.Reading()
        // Strings of a row each, as a database gives them; the works by their numbers
        rows.forEach { (id, facet, key) ->
            reading.add(id, String(facet.toCharArray()), String(key.toCharArray()))
        }
        val works = reading.done()
        val tables = HashMap<Long, HashMap<String, MutableList<String>>>()
        rows.forEach { (id, facet, key) -> tables.getOrPut(id) { HashMap() }.getOrPut(facet) { ArrayList() } += key }
        assertEquals<Map<Long, Map<String, List<String>>>>(tables, works)
        tables.forEach { (id, own) ->
            assertEquals<Map<String, List<String>>>(own, works.getValue(id))
            assertEquals(own.hashCode(), works.getValue(id).hashCode())
        }
        val w1 = works.getValue(1L)
        assertEquals(listOf("a", "b"), w1["tags"])
        assertNull(w1["rating"])
        assertTrue("fandom" in w1)
        assertEquals(listOf("tags", "fandom"), w1.keys.toList())
        // One string of a facet's name and of a value's key, whatever work has it
        assertSame(w1.keys.first(), works.getValue(2L).keys.last())
        assertSame(w1.getValue("fandom")[0], works.getValue(2L).getValue("fandom")[0])
    }
}
