package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/** A work of several items is shown with the one that says the most of it, as measured and kept with its binding. */
class WorkFullnessTest {

    @TempDir lateinit var folder: Path

    @Test
    fun `the items of a work are read the one saying the most first, whatever work they were merged into`() {
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val works = WorkRepository(dictionaries.dsl)
            val now = Instant.now()
            works.bindNew("a", mapOf("1" to "v", "2" to "v"), now)
            works.bindNew("b", mapOf("3" to "v"), now)
            assertEquals(listOf("1", "2"), works.unmeasured("a"))
            works.measured("a", mapOf("1" to 5L, "2" to 70L))
            works.membership.merge(works.of(ItemKey("a", "2"), now), works.of(ItemKey("a", "1"), now), now)
            works.membership.merge(works.of(ItemKey("b", "3"), now), works.of(ItemKey("a", "1"), now), now)

            // Never measured, last; the one saying the most first
            val clusters = WorkClusters.of(works.groups(listOf("a", "b")).values.toList())
            assertEquals(
                listOf(ItemKey("a", "2"), ItemKey("a", "1"), ItemKey("b", "3")),
                clusters.fullestFirst(ItemKey("b", "3")),
            )
            assertEquals(ItemKey("a", "2"), clusters.shown(ItemKey("a", "1")))
            assertEquals(ItemKey("a", "1"), clusters.representative(ItemKey("b", "3")))

            works.measured("b", mapOf("3" to 900L))
            assertEquals(emptyList(), works.unmeasured("b"))
            assertEquals(
                ItemKey("b", "3"),
                WorkClusters.of(works.groups(listOf("a", "b")).values.toList()).shown(ItemKey("a", "2")),
            )
        }
    }
}
