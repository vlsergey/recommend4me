package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ListFacetValuesTest {
    private fun row(id: String, facets: Map<String, List<String>>, words: Double? = null) =
        ListRow(
            key = ItemKey("site", id),
            work = ItemKey("site", id),
            rank = 0,
            linked = false,
            updatedAt = Instant.EPOCH,
            firstSeenAt = Instant.EPOCH,
            values = RowValues(false, null, null, null),
            facets = facets,
            numbers = listOfNotNull(words?.let { "words" to it }).toMap(),
            title = id,
        )

    /** Every item's values of the index, as written last — a value of none, an item of none, one written again. */
    private fun every(index: ListIndex): Map<String, Set<String>> =
        ListFacetValues.everyItem(index, "site", listOf("tags", "kind"), listOf("words")).let { rows ->
            rows.ids.indices.associate { i -> rows.ids[i] to rows.values[i].map { rows.names[it] }.toSet() }
        }

    @Test
    fun `every item's values read of the doc values, by number, as written last`() {
        ListIndex(Files.createTempDirectory("list-index")).use { index ->
            index.replace(
                listOf(
                    row("a", mapOf("tags" to listOf("t1", "t2"), "kind" to listOf("k"))),
                    row("b", mapOf("tags" to emptyList(), "kind" to listOf("k")), words = 900.0),
                    row("c", mapOf("tags" to listOf("t2"))),
                )
            )
            index.commit()
            assertEquals(
                mapOf(
                    "a" to setOf("tags:t1", "tags:t2", "kind:k"),
                    "b" to setOf("kind:k"),
                    "c" to setOf("tags:t2"),
                ),
                every(index),
            )
            // The numbers by the items' places, nought of one not known
            val sized = ListFacetValues.everyItem(index, "site", emptyList(), listOf("words"))
            assertEquals(listOf("a", "b", "c"), sized.ids)
            assertEquals(listOf(0.0, 900.0, 0.0), sized.numbers.getValue("words").asList())
            // An item written again with a value more, in a segment of its own
            index.replace(listOf(row("b", mapOf("tags" to listOf("t3"), "kind" to listOf("k")))))
            index.commit()
            assertEquals(setOf("tags:t3", "kind:k"), every(index)["b"])
        }
    }
}
