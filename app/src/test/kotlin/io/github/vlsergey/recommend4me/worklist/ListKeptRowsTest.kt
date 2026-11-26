package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListKeptRowsTest {
    private fun row(
        id: Long,
        facets: Map<String, List<String>> = mapOf("tags" to listOf("t1", "t2"), "kind" to emptyList()),
        numbers: Map<String, Double> = mapOf("words" to 900.0, "chars" to 5400.5),
        title: String = "The title of $id",
        linked: Boolean = true,
        graded: Boolean = false,
    ) =
        ListRow(
            key = ItemKey("site", id),
            work = ItemKey("site", 1L),
            rank = if (id == 1L) 0 else 1,
            linked = linked,
            updatedAt = Instant.ofEpochMilli(1_700_000_000_123),
            firstSeenAt = Instant.ofEpochMilli(1_600_000_000_456),
            values = RowValues(graded, 7.2345, 0.4567, 3.3),
            facets = facets,
            numbers = numbers,
            title = title,
        )

    @Test
    fun `a document read as written is its row's, and is no longer of a row changed in anything it holds`() {
        temporaryIndex { index ->
            val a = row(1L)
            val b = row(2L, linked = false, numbers = emptyMap(), facets = mapOf("tags" to listOf("t3")))
            index.replace(listOf(a, b))
            index.commit()
            val kept = index.written { ListKeptRows.of(it, "site", listOf(1L, 2L, 99L)) }
            assertEquals(setOf(1L, 2L), kept.keys)
            assertTrue(a.sameAs(kept.getValue(1L)))
            assertTrue(b.sameAs(kept.getValue(2L)))
            val written = kept.getValue(1L)
            assertFalse(row(1L, facets = mapOf("tags" to listOf("t1"))).sameAs(written), "a value fewer")
            assertFalse(row(1L, facets = mapOf("tags" to listOf("t1", "t2"), "kind" to listOf("k"))).sameAs(written))
            assertFalse(row(1L, numbers = mapOf("words" to 901.0, "chars" to 5400.5)).sameAs(written), "a number")
            assertFalse(row(1L, title = "Another").sameAs(written), "the title")
            assertFalse(row(1L, linked = false).sameAs(written), "of a work of one item")
            assertFalse(row(1L, graded = true).sameAs(written), "graded")
        }
    }

    @Test
    fun `the order of a facet's values is no change — neither another row nor a dictionary's draft`() {
        temporaryIndex { index ->
            index.replace(listOf(row(1L, facets = mapOf("tags" to listOf("t1", "t2", "t3")))))
            index.commit()
            val written = index.written { ListKeptRows.of(it, "site", listOf(1L)) }.getValue(1L)
            // The drafts of a work read by its key and of every work come in orders of their own
            val reordered = row(1L, facets = mapOf("tags" to listOf("t3", "t1", "t2")))
            assertTrue(reordered.sameAs(written), "the same values in another order")
            assertFalse(reordered.draftedOtherThan(written, setOf("tags")), "no draft of a dictionary changed")
            assertTrue(row(1L, facets = mapOf("tags" to listOf("t1", "t4"))).draftedOtherThan(written, setOf("tags")))
        }
    }
}
