package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.NumberJoin
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WorkFeaturesTest {

    private val a = ItemKey("alpha", "1")
    private val b = ItemKey("beta", "2")

    /** Of a number both items have, the mean; of the version, that of the item that names it. */
    @Test
    fun `a work of two items has what either has`() {
        val first =
            CatalogueItem(
                a,
                "v1",
                Instant.ofEpochSecond(10),
                setOf("tag:x", "source:alpha"),
                mapOf("words" to 10f, "only" to 3f),
            )
        val second =
            CatalogueItem(b, "v2", Instant.ofEpochSecond(20), setOf("tag:y", "source:beta"), mapOf("words" to 20f))
        val work = WorkFeatures.item(a, listOf(first, second))
        assertEquals(a, work.key)
        assertEquals("v1", work.version)
        assertEquals(Instant.ofEpochSecond(20), work.updatedAt)
        assertEquals(setOf("tag:x", "tag:y", "source:alpha", "source:beta"), work.categorical)
        assertEquals(mapOf("words" to 15f, "only" to 3f), work.numeric)
    }

    /** A yes or no every site says: 1 where all say yes, 0 where all say no, 0.5 where they differ, however many. */
    @Test
    fun `a number joined by agreement is the items' one value, else the middle`() {
        val joins = mapOf("closed" to NumberJoin.AGREEMENT)
        fun work(vararg values: Float) =
            WorkFeatures.item(
                    a,
                    values.mapIndexed { i, v ->
                        CatalogueItem(
                            if (i == 0) a else ItemKey("s$i", "$i"),
                            "",
                            Instant.EPOCH,
                            emptySet(),
                            mapOf("closed" to v),
                        )
                    },
                    joins,
                )
                .numeric
                .getValue("closed")
        assertEquals(1f, work(1f, 1f, 1f))
        assertEquals(0f, work(0f, 0f))
        assertEquals(0.5f, work(1f, 1f, 0f))
        assertEquals(0.5f, work(0f, 1f))
    }

    @Test
    fun `of a block two items have the mean, of a block one has its own, a single item as it is`() {
        val first = mapOf("title" to floatArrayOf(1f, 3f), "pictures" to floatArrayOf(5f))
        val second = mapOf("title" to floatArrayOf(3f, 5f))
        val work = WorkFeatures.vectors(listOf(first, second))
        assertContentEquals(floatArrayOf(2f, 4f), work.getValue("title"))
        assertContentEquals(floatArrayOf(5f), work.getValue("pictures"))
        assertEquals(first, WorkFeatures.vectors(listOf(first)))
    }
}
