package io.github.vlsergey.recommend4me.part

import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The texts of the parts and their vectors kept outside the database ([PartStore]): read back as written — a vector
 * within half precision, a paragraph not encoded a gap — and every one of a work gone with it, another work's kept.
 */
class PartStoreTest {

    @Test
    fun `texts and vectors read back as written, a work's gone with it alone`() {
        val folder = Files.createTempDirectory("parts")
        PartStore(folder).use { store ->
            store.putText("w1", "p1", "Lorem ipsum dolor sit amet")
            store.putText("w1", "p2", "Consectetur adipiscing elit")
            store.putText("w10", "p1", "Sed do eiusmod")
            store.putWindows("w1", "p1", listOf(floatArrayOf(0.5f, -1f), floatArrayOf(2f, 0.25f)))
            store.putParagraphs("w1", "p1", listOf(floatArrayOf(1f, 0f), null), listOf("h0", "h1"))

            assertEquals("Lorem ipsum dolor sit amet", store.text("w1", "p1"))
            assertEquals(
                mapOf("p1" to "Lorem ipsum dolor sit amet", "p2" to "Consectetur adipiscing elit"),
                store.texts("w1", listOf("p1", "p2", "p3")),
            )
            val windows = store.windows("w1", listOf("p1")).getValue("p1")
            assertEquals(2, windows.size)
            assertContentEquals(floatArrayOf(2f, 0.25f), windows[1])
            val paragraphs = store.paragraphs("w1", listOf("p1")).getValue("p1")
            assertEquals("h0", paragraphs[0]!!.hash)
            assertNull(paragraphs[1])
            assertTrue(store.windows("w1", listOf("p2")).isEmpty())

            // The work "w1" removed: "w10", whose key begins alike, stays
            store.forget("w1")
            assertNull(store.text("w1", "p1"))
            assertTrue(store.windows("w1", listOf("p1")).isEmpty())
            assertTrue(store.paragraphs("w1", listOf("p1")).isEmpty())
            assertEquals("Sed do eiusmod", store.text("w10", "p1"))
        }
        // Kept across a reopening
        PartStore(folder).use { assertEquals("Sed do eiusmod", it.text("w10", "p1")) }
    }

    @Test
    fun `the vectors of many parts kept apart from their keys in a few shared files, not a file a part`() {
        val folder = Files.createTempDirectory("parts")
        val parts = 300
        val vector = FloatArray(VECTOR) { it / VECTOR.toFloat() }
        PartStore(folder).use { store ->
            repeat(parts) { store.putWindows("w$it", "p1", List(WINDOWS) { vector }) }
        }
        // Reopened: what the log held flushed, the large values into files of their own
        PartStore(folder).use { store ->
            repeat(parts) { assertEquals(WINDOWS, store.windows("w$it", listOf("p1")).getValue("p1").size) }
            assertContentEquals(vector, store.windows("w7", listOf("p1")).getValue("p1")[3], "within half precision")
        }
        val blobs = Files.walk(folder).use { files -> files.filter { it.toString().endsWith(".blob") }.count() }
        assertTrue(blobs in 1..2, "$blobs files of the vectors of $parts parts")
    }

    private companion object {
        /** Numbers of a vector, and windows of a part: a part's vectors are tens of kilobytes. */
        const val VECTOR = 1024
        const val WINDOWS = 24
    }
}
