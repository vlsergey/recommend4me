package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WorkFeaturesTest {

    private val a = ItemKey("alpha", "1")
    private val b = ItemKey("beta", "2")

    @Test
    fun `a work of two items has what either has, of a number both have the mean, the version of the item that names it`() {
        val first = CatalogueItem(a, "v1", Instant.ofEpochSecond(10), setOf("tag:x", "source:alpha"), mapOf("words" to 10f, "only" to 3f))
        val second = CatalogueItem(b, "v2", Instant.ofEpochSecond(20), setOf("tag:y", "source:beta"), mapOf("words" to 20f))
        val work = WorkFeatures.item(a, listOf(first, second))
        assertEquals(a, work.key)
        assertEquals("v1", work.version)
        assertEquals(Instant.ofEpochSecond(20), work.updatedAt)
        assertEquals(setOf("tag:x", "tag:y", "source:alpha", "source:beta"), work.categorical)
        assertEquals(mapOf("words" to 15f, "only" to 3f), work.numeric)
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
