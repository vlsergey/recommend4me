package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pages of the site read. Their prose — descriptions, notes, chapters, reviews — is Lorem ipsum in the pages kept
 * for the tests, and the authors' and reviewers' names neutral: the structure is the site's, the words are not.
 */
class MolwePagesTest {

    private val source = Molwe()
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `a tile of a list tells a work's badges, its first fandom, its description and its readers`() {
        val context = MemorySourceContext()
        val read = capture(context, "https://molwe.ru/fanfics?page=2", "list-tiles.html")
        assertEquals(24, read.size)
        assertEquals("20645", read.first())
        val id = "20609"
        assertEquals("Опять твои заброшки…Не надоело ещё?", context.heads.getValue(id).title)
        assertEquals("https://molwe.ru/fanfics/20609", context.heads.getValue(id).url)
        val facets = context.facets.getValue(id)
        assertEquals(listOf(AgeRating.NC17.key), facets[AgeRating.SITE_KEY])
        assertEquals(listOf(AgeRating.NC17.key), facets[AgeRating.KEY])
        assertEquals(listOf(Books.Direction.HET.key), facets["direction"])
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        assertEquals(listOf("видеоблогеры"), facets["fandom"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        // A tile names no author, no tags, no characters
        assertNull(facets["author"])
        assertNull(facets["tag"])
        assertNull(facets["pairing"])
        assertEquals(228.0, context.numbers.getValue(id)["readers"])
        assertTrue(context.texts.getValue(id)["annotation"].orEmpty().startsWith("Lorem ipsum"))
        // The site's picture of no cover is no cover
        assertEquals(emptyList(), context.pictures.getValue(id))
    }

    @Test
    fun `a row of a list tells its author, size, tags and reviews too, its characters and pairings not picked out`() {
        val context = MemorySourceContext()
        val read = capture(context, "https://molwe.ru/fanfics?page=2", "list-rows.html")
        assertEquals(24, read.size)
        val id = "20609"
        val facets = context.facets.getValue(id)
        assertEquals(listOf("34196"), facets["author"])
        assertEquals("Author 4", context.facetNames.getValue("author")["34196"])
        assertEquals(listOf(AgeRating.NC17.key), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("видеоблогеры"), facets["fandom"])
        // The names a row runs together by commas are no values: the work's page gives each of its own
        assertNull(facets["pairing"])
        assertNull(context.texts[id]?.get("pairingsLine"))
        assertEquals(9, facets["tag"].orEmpty().size)
        assertEquals("Hurt/Comfort", context.facetNames.getValue("tag")["hurt/comfort"])
        assertEquals(68715.0, context.numbers.getValue(id)["words"])
        assertEquals(0.0, context.numbers.getValue(id)["reviews"])
        assertTrue(context.texts.getValue(id)["annotation"].orEmpty().startsWith("Lorem ipsum"))
    }

    @Test
    fun `a work's page gives its header, description, cast, tags, notes and every chapter named`() {
        val context = MemorySourceContext()
        val id = "20627"
        val read = capture(context, source.itemUrl(id), "work.html")
        assertTrue(id in read, "$read")
        // The works like it, cards of their own
        assertEquals(11, read.size)
        val head = context.heads.getValue(id)
        assertEquals("Притворись моей...", head.title)
        val facets = context.facets.getValue(id)
        assertEquals(listOf(AgeRating.R.key), facets[AgeRating.SITE_KEY])
        assertEquals(listOf(Books.Direction.HET.key), facets["direction"])
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), facets["status"])
        assertEquals(listOf("щерба наталья «часодеи»"), facets["fandom"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertEquals(listOf("34202"), facets["author"])
        assertEquals(listOf("василиса/фэшиар драгоций", "захарра драгоций/ник лазарев"), facets["pairing"])
        assertEquals(
            "Василиса / Фэшиар Драгоций, Захарра Драгоций / Ник Лазарев",
            context.texts.getValue(id)["pairingsLine"],
        )
        assertEquals(7, facets["tag"].orEmpty().size)
        assertTrue("нецензурная лексика 18+" in facets["tag"].orEmpty())
        assertTrue(context.texts.getValue(id)["annotation"].orEmpty().startsWith("Lorem ipsum"))
        assertTrue(context.texts.getValue(id)["notes"].orEmpty().startsWith("Lorem ipsum"))
        assertEquals(189.0, context.numbers.getValue(id)["readers"])
        assertEquals(0.0, context.numbers.getValue(id)["reviews"])
        assertEquals(46.0, context.numbers.getValue(id)["parts"])
        val parts = context.parts.getValue(id).values.sortedBy { it.position }
        assertEquals(46, parts.size)
        assertEquals("83fefa5f-4d3c-4e85-b8f5-77f04c306abe", parts.first().id)
        assertEquals(0, parts.first().position)
        assertEquals("Пролог", parts.first().title)
        assertNull(parts.first().content)
        assertEquals(Instant.parse("2026-11-05T21:00:00Z"), parts.first().publishedAt)
        assertEquals(emptyList(), context.pictures.getValue(id))
    }

    @Test
    fun `an original work's page tells it by its fandom and gives its reviews loaded`() {
        val context = MemorySourceContext()
        val id = "20656"
        assertTrue(id in capture(context, "https://molwe.ru/originals/20656", "original.html"))
        val facets = context.facets.getValue(id)
        assertEquals(listOf("ориджинал"), facets["fandom"])
        assertEquals(listOf(UniverseFacets.ORIGINAL), facets[UniverseFacets.KIND])
        assertEquals(listOf(Books.Direction.GEN.key), facets["direction"])
        // "Пэйринги не указаны": none
        assertEquals(emptyList(), facets["pairing"])
        assertNull(context.texts.getValue(id)["pairingsLine"])
        // A tag of no page of its own is a tag too
        assertTrue("темные начала" in facets["tag"].orEmpty() && "ужасы" in facets["tag"].orEmpty())
        val reviews = context.reviews.getValue(id).values
        assertEquals(1, reviews.size)
        assertEquals("29162", reviews.first().id)
        assertEquals("Reader 1", reviews.first().author)
        assertTrue(reviews.first().content.startsWith("Lorem ipsum"))
        assertEquals(
            listOf("https://api-fanfic.molwe.ru/api/media/84cc44b7-7524-47ac-8a98-a8f41d1b47c8.webp"),
            context.pictures.getValue(id),
        )
    }

    @Test
    fun `a chapter's page gives its text, of a work known`() {
        val context = MemorySourceContext()
        val id = "20629"
        val url = source.partUrl(id, "d20210ed-78e3-43e3-87ec-9213873f915c")
        assertEquals(emptyList(), capture(context, url, "chapter.html"))
        context.items.upsert(ItemHead(id, source.itemUrl(id), "Влюбись в меня, если осмелишься"), now)
        assertEquals(listOf(id), capture(context, url, "chapter.html"))
        val part = context.parts.getValue(id).getValue("d20210ed-78e3-43e3-87ec-9213873f915c")
        // Its place in the work is told by the work's page alone
        assertEquals(-1, part.position)
        assertEquals("Глава 2", part.title)
        assertTrue(part.content.orEmpty().startsWith("Lorem ipsum"))
        assertEquals(38, part.content.orEmpty().split("\n\n").size)
    }

    @Test
    fun `an author's page gives the works they wrote`() {
        val context = MemorySourceContext()
        assertEquals(listOf("20656"), capture(context, "https://molwe.ru/author/34129", "author.html"))
        val facets = context.facets.getValue("20656")
        assertEquals(listOf("34129"), facets["author"])
        assertEquals("Author 2", context.facetNames.getValue("author")["34129"])
        assertEquals(listOf(UniverseFacets.ORIGINAL), facets[UniverseFacets.KIND])
    }

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals("20627", source.itemIdOf("https://molwe.ru/fanfics/20627"))
        assertEquals("20656", source.itemIdOf("https://molwe.ru/originals/20656"))
        assertEquals(
            "20627",
            source.itemIdOf("https://molwe.ru/fanfics/20627/read/83fefa5f-4d3c-4e85-b8f5-77f04c306abe"),
        )
        assertNull(source.itemIdOf("https://molwe.ru/fanfics/tags/1683"))
        assertNull(source.itemIdOf("https://molwe.ru/fanfics?page=2"))
        assertTrue(source.capturePatterns.any { it.matches("https://molwe.ru/") })
        assertTrue(source.capturePatterns.none { it.matches("https://molwe.ru/wiki") })
    }
}
