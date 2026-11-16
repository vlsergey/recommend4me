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
        val joins = WorkFeatures.Joins(mapOf("closed" to NumberJoin.AGREEMENT))
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

    /** The size of a work is the greatest any of its items tells; a number of no join the mean still. */
    @Test
    fun `a number joined by its greatest is the greatest the items have`() {
        val joins = WorkFeatures.Joins(mapOf("num:words" to NumberJoin.MAX))
        val work =
            WorkFeatures.item(
                a,
                listOf(
                    CatalogueItem(a, "", Instant.EPOCH, emptySet(), mapOf("num:words" to 7f, "num:likes" to 2f)),
                    CatalogueItem(b, "", Instant.EPOCH, emptySet(), mapOf("num:words" to 9f, "num:likes" to 4f)),
                ),
                joins,
            )
        assertEquals(mapOf("num:words" to 9f, "num:likes" to 3f), work.numeric)
    }

    /**
     * The status of a work is the greatest any of its items tells — finished before in progress before frozen; the
     * values of another facet all kept.
     */
    @Test
    fun `of a facet joined by the value listed first that one alone is kept`() {
        val joins = WorkFeatures.Joins(firstListed = mapOf("status" to listOf("finished", "in-progress", "frozen")))
        fun work(vararg statuses: String) =
            WorkFeatures.item(
                    a,
                    statuses.mapIndexed { i, status ->
                        CatalogueItem(
                            if (i == 0) a else ItemKey("s$i", "$i"),
                            "",
                            Instant.EPOCH,
                            setOf(FeatureNames.facet("status", status), FeatureNames.facet("tag", "t$i")),
                            emptyMap(),
                        )
                    },
                    joins,
                )
                .categorical
                .filter { it.startsWith("facet:status:") }
                .toSet()
        assertEquals(setOf("facet:status:finished"), work("frozen", "finished", "in-progress"))
        assertEquals(setOf("facet:status:in-progress"), work("frozen", "in-progress"))
        assertEquals(setOf("facet:status:frozen"), work("frozen"))
        assertEquals(
            setOf("facet:tag:t0", "facet:tag:t1"),
            WorkFeatures.item(
                    a,
                    listOf(
                        CatalogueItem(a, "", Instant.EPOCH, setOf("facet:tag:t0"), emptyMap()),
                        CatalogueItem(b, "", Instant.EPOCH, setOf("facet:tag:t1"), emptyMap()),
                    ),
                    joins,
                )
                .categorical,
        )
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
