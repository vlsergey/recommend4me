package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.source.PartData
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chapter a whole table of contents no longer names is taken off the site (owner, 2026-11-20: "the chapters of the
 * work became fewer, but the application does not follow it"): its text kept and shown, nothing of the work made of it
 * — its words, its texts read, its vectors — and it is the work's again once a page names it.
 */
class PartTakenOffTest {

    @Test
    fun `a chapter left out of a whole table of contents taken off, kept, and back once named`() {
        val folder = Files.createTempDirectory("part-taken-off")
        PartStore(folder).use { store ->
            Database("test/source", folder.resolve("source"), "source").use { db ->
                db.dsl.execute(
                    "INSERT INTO item (site_id, url, title, version, updated_at, first_seen_at) " +
                        "VALUES ('w1', 'u', 't', '', NOW(), NOW())"
                )
                val parts = PartRepository(db.dsl, store)
                parts.save("w1", listOf(PartData("c1", 0, "One", "раз два"), PartData("c2", 1, "Two", "три")))
                parts.windows.saveVectors("w1", "c2", "enc", "h", listOf(floatArrayOf(1f, 2f)))
                // A page that names one chapter and is no table of contents takes nothing off
                assertFalse(parts.save("w1", listOf(PartData("c1", -1, null, null))))
                assertEquals(3.0, parts.counts.counted().getValue("w1").first)

                val at = Instant.parse("2026-11-20T12:00:00Z")
                assertTrue(parts.save("w1", listOf(PartData("c1", 0, "One", null)), whole = true, now = at))
                val two = parts.ofItem("w1").single { it.partId == "c2" }
                assertEquals(at, two.takenOffAt)
                assertTrue(two.hasText)
                assertEquals("три", parts.text("w1", "c2")!!.second)
                assertEquals(2.0, parts.counts.counted().getValue("w1").first)
                assertEquals(listOf("c1"), parts.titlesOf(listOf("w1")).getValue("w1").map { it.first })
                assertNull(parts.windows.windowCountsOf(listOf("w1"), "enc")["w1"])
                assertEquals(listOf("c1"), parts.marks.of(listOf("w1")).getValue("w1").map { it.partId })
                // The same table read again changes nothing
                assertFalse(parts.save("w1", listOf(PartData("c1", 0, "One", null)), whole = true))

                assertTrue(parts.save("w1", listOf(PartData("c1", 0, null, null), PartData("c2", 1, null, null))))
                assertNull(parts.ofItem("w1").single { it.partId == "c2" }.takenOffAt)
                assertEquals(3.0, parts.counts.counted().getValue("w1").first)
                assertNotNull(parts.windows.windowCountsOf(listOf("w1"), "enc")["w1"])
            }
        }
    }
}
