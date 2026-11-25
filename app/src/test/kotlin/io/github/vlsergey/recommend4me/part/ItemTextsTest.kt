package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.SiteTestBase
import io.github.vlsergey.recommend4me.source.PartData
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The texts of every item of a source read a portion of parts a query: an item's parts split between two portions read
 * whole, in the order of its parts, every item once.
 */
class ItemTextsTest : SiteTestBase() {

    @Test
    fun `the texts of every item read whole across the portions, in the order of its parts`() {
        val store = stores.source(site)!!
        val parts = mapOf("itx1" to 3, "itx2" to 1, "itx3" to 2)
        parts.forEach { (id, n) ->
            capture("$id|The texts of $id|space|A work of parts||")
            // The parts' ids in another order than their places: read in the order of their places
            store.parts.save(id, (1..n).map { k -> PartData("p${n - k}", k, "Part $k", "Part $k of $id", null) })
        }
        val read = LinkedHashMap<String, List<String>>()
        store.parts.texts.forEach(portion = 2) { id, texts -> if (id in parts) read[id] = texts }
        assertEquals(parts.keys.toList(), read.keys.toList(), "every item once, in the order of the key")
        parts.forEach { (id, n) -> assertEquals((1..n).map { "Part $it of $id" }, read[id], id) }
    }
}
