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
            Triple("w1", "tags", "a"),
            Triple("w2", "fandom", "hp"),
            Triple("w1", "fandom", "hp"),
            Triple("w1", "tags", "b"),
            Triple("w2", "tags", "a"),
            Triple("w3", "rating", "pg-13"),
        )

    @Test
    fun `every work's facets as the rows give them, in the order read`() {
        val reading = WorkFacets.Reading()
        // Strings of a row each, as a database gives them
        rows.forEach { (id, facet, key) ->
            reading.add(String(id.toCharArray()), String(facet.toCharArray()), String(key.toCharArray()))
        }
        val works = reading.done()
        val tables = HashMap<String, HashMap<String, MutableList<String>>>()
        rows.forEach { (id, facet, key) -> tables.getOrPut(id) { HashMap() }.getOrPut(facet) { ArrayList() } += key }
        assertEquals<Map<String, Map<String, List<String>>>>(tables, works)
        tables.forEach { (id, own) ->
            assertEquals<Map<String, List<String>>>(own, works.getValue(id))
            assertEquals(own.hashCode(), works.getValue(id).hashCode())
        }
        val w1 = works.getValue("w1")
        assertEquals(listOf("a", "b"), w1["tags"])
        assertNull(w1["rating"])
        assertTrue("fandom" in w1)
        assertEquals(listOf("tags", "fandom"), w1.keys.toList())
        // One string of a facet's name and of a value's key, whatever work has it
        assertSame(w1.keys.first(), works.getValue("w2").keys.last())
        assertSame(w1.getValue("fandom")[0], works.getValue("w2").getValue("fandom")[0])
    }
}
