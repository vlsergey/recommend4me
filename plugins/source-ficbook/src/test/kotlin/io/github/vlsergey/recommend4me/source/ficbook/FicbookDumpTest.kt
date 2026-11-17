package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The import of a dump of the site ([FicbookDump]) of the test's dump, `dump/0000.parquet` among the resources: the
 * schema of the dataset IlyaGusev/ficbook, four works made up, their texts Lorem ipsum — a work of two parts of a known
 * author and fandom and an unknown author, an original one of one part, one the application has already, and a row of
 * no work's address.
 */
class FicbookDumpTest {
    private class TestJob(val kept: MutableMap<String, String> = HashMap()) : Job {
        var found = 0
        var processed = 0
        var total = 0
        var message: String? = null

        override fun phase(name: String, total: Int, processed: Int) {
            this.total = total
            this.processed = processed
        }

        override fun progress(processed: Int, total: Int?) {
            this.processed = processed
        }

        override fun found(newItems: Int) {
            found += newItems
        }

        override fun error(message: String) = Unit

        override fun message(key: String?) {
            message = key
        }

        override fun session(loggedIn: Boolean?) = Unit

        override val cancelled = false

        override fun checkpoint() = Unit

        override fun state(key: String): String? = kept[key]

        override fun state(key: String, value: String?) {
            if (value == null) kept.remove(key) else kept[key] = value
        }
    }

    private fun withDump(): MemorySourceContext =
        MemorySourceContext(Ficbook.ID).also { c ->
            val folder = Files.createDirectories(c.folder.resolve("dump"))
            javaClass.getResourceAsStream("/dump/0000.parquet")!!.use { Files.copy(it, folder.resolve("0000.parquet")) }
            // An author and a fandom the site's own pages gave before
            c.items.setFacet("1", Ficbook.AUTHOR, listOf(FacetValue("777", "Known Author")))
            c.items.setFacet("1", Ficbook.FANDOM, listOf(FacetValue("books/known", "Known Fandom")))
            // A work the application has, the text of its second part not read
            c.items.upsert(ItemHead("103", "${Ficbook.BASE}/readfic/103", "Site title"), Instant.EPOCH)
            c.items.setTexts("103", mapOf(Ficbook.ANNOTATION to "Site annotation"))
            c.items.saveParts("103", listOf(PartData("9301", 0, "One", "Site text"), PartData("9302", 1, "Two", null)))
        }

    @Test
    fun `a new work as a page would write it, its authors and fandom by the keys known or by their names`() {
        val c = withDump()
        val job = TestJob()
        Ficbook().scrape(SourceMode.IMPORT, c, job)

        assertEquals("Lorem", c.heads.getValue("101").title)
        assertEquals(Instant.parse("2012-04-15T00:00:00Z"), c.heads.getValue("101").updatedAt)
        val facets = c.facets.getValue("101")
        assertEquals(listOf("777", "${DumpWrites.NAME_KEY}New Author"), facets[Ficbook.AUTHOR])
        assertEquals(listOf("books/known"), facets[Ficbook.FANDOM])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertEquals(listOf("het"), facets[Ficbook.DIRECTION])
        assertEquals(listOf("pg-13"), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("finished"), facets[Ficbook.STATUS])
        assertEquals(listOf("романтика", "флафф"), facets[Ficbook.TAG])
        assertEquals(mapOf(Ficbook.LIKES to 42.0, Ficbook.PARTS to 2.0), c.numbers.getValue("101"))
        // The line of pairings kept whole as a text, never parsed into values
        assertEquals("Alpha/Beta, Gamma", c.texts.getValue("101")[Ficbook.PAIRINGS_LINE])
        assertNull(facets[Ficbook.PAIRING])
        assertEquals(listOf("9001", "9002"), c.parts.getValue("101").keys.toList())
        // Of a work of one part, its text the work's own page's
        assertEquals(setOf(Ficbook.ONLY_PART), c.parts.getValue("102").keys)
        assertEquals(listOf(FicbookFields.ORIGINALS), c.facets.getValue("102")[Ficbook.FANDOM])
        assertEquals(listOf(UniverseFacets.ORIGINAL), c.facets.getValue("102")[UniverseFacets.KIND])
        // The row of no work's address left out; the works written a batch at a time, the place reached let go at the
        // end
        assertEquals(setOf("101", "102", "103"), c.heads.keys)
        assertEquals(2, job.found)
        assertEquals(4, job.processed)
        assertEquals(4, job.total)
        assertEquals(1, c.batches)
        assertEquals(emptyMap(), job.kept)
    }

    @Test
    fun `of a work known, only the texts of the parts it lacks`() {
        val c = withDump()
        Ficbook().scrape(SourceMode.IMPORT, c, TestJob())

        assertEquals("Site title", c.heads.getValue("103").title)
        assertEquals(mapOf(Ficbook.ANNOTATION to "Site annotation"), c.texts.getValue("103"))
        assertNull(c.facets["103"])
        val parts = c.parts.getValue("103")
        assertEquals("Site text", parts.getValue("9301").content)
        assertEquals(1, parts.getValue("9302").position)
        assertEquals(true, parts.getValue("9302").content?.startsWith("Lorem ipsum"))
    }

    @Test
    fun `an import goes on from the place a stop left`() {
        val c = withDump()
        val job = TestJob(mutableMapOf("dump.file" to "0000.parquet", "dump.row" to "2"))
        Ficbook().scrape(SourceMode.IMPORT, c, job)

        assertEquals(setOf("103"), c.heads.keys)
        assertEquals(0, job.found)
    }

    @Test
    fun `no dump, the job says so`() {
        val job = TestJob()
        Ficbook().scrape(SourceMode.IMPORT, MemorySourceContext(Ficbook.ID), job)

        assertEquals("noDump", job.message)
    }
}
