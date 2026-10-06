package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FicbookPagesTest {

    private val source = Ficbook()
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private val work = "019fc2ac-ce89-7afa-aab8-b51d74fc50f7"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `the cards of a list give the works, the promoted ones left out`() {
        val context = MemorySourceContext()
        assertEquals(listOf(work, "9876543"), capture(context, "https://ficbook.net/fanfiction/no_fandom/originals", "fandom-originals.html"))
        assertEquals("Нас связали шрамами. Тихий дом (Том 1)", context.heads.getValue(work).title)
        assertEquals(Instant.parse("2026-08-03T21:00:00Z"), context.heads.getValue(work).updatedAt)
        val facets = context.facets.getValue(work)
        assertEquals(listOf("019492f9-69b1-7040-8a90-9de7e3cbb01e"), facets["author"])
        assertEquals(listOf("no_fandom/originals"), facets["fandom"])
        assertEquals(listOf("mixed"), facets["direction"])
        assertEquals(listOf("NC-17"), facets["rating"])
        assertEquals(listOf("finished"), facets["status"])
        assertEquals(listOf("28510"), facets["series"])
        assertTrue("слоуберн" in facets.getValue("tag"))
        // A pairing is one whatever the order of its names; its name stays as written
        assertEquals(listOf("авелин/брайан", "альфред/итан"), facets["pairing"])
        assertEquals("Брайан/Авелин", context.facetNames.getValue("pairing")["авелин/брайан"])
        assertEquals(listOf("брайан", "авелин", "альфред", "итан", "фрида", "сия", "терен", "бабушка рико"), facets["character"])
        assertTrue(context.texts.getValue(work).getValue(Ficbook.PAIRINGS_LINE).startsWith("Брайан/Авелин"))
        val numbers = context.numbers.getValue(work)
        assertEquals(mapOf("likes" to 2.0, "pages" to 180.0, "words" to 68967.0, "parts" to 20.0), numbers)
        assertTrue(context.texts.getValue(work).getValue("annotation").startsWith("Молодой хирург"))
        assertEquals(listOf("rouling_dzhoan___garri_potter".let { "books/$it" }, "anime_and_manga/naruto"), context.facets.getValue("9876543")["fandom"])
        assertEquals(Instant.parse("2026-08-02T14:55:00Z"), context.heads.getValue("9876543").updatedAt)
    }

    @Test
    fun `a work's page gives its header and table of contents`() {
        val context = MemorySourceContext()
        assertEquals(listOf(work), capture(context, "https://ficbook.net/readfic/$work", "readfic.html"))
        val texts = context.texts.getValue(work)
        assertEquals("Я вынашивала эту историю в своей голове около 20-ти лет.", texts["notes"])
        assertEquals("Посвящаю эту работу моему любимому дедушке.", texts["dedication"])
        assertEquals(listOf("019492f9-69b1-7040-8a90-9de7e3cbb01e"), context.facets.getValue(work)["author"])
        assertEquals(2.0, context.numbers.getValue(work)["comments"])
        val parts = context.parts.getValue(work)
        assertEquals(listOf("42501445", "42505773"), parts.keys.toList())
        assertEquals("1. Печенье и лекарства", parts.getValue("42501445").title)
        assertNull(parts.getValue("42501445").content)
        // The promoted work of the page is not one of the user's
        assertEquals(setOf(work), context.heads.keys)
    }

    @Test
    fun `a part gives its text, and comments are the readers' opinions`() {
        val context = MemorySourceContext()
        capture(context, "https://ficbook.net/readfic/$work", "readfic.html")
        assertEquals(listOf(work), capture(context, "https://ficbook.net/readfic/$work/42501445#part_content", "part.html"))
        val part = context.parts.getValue(work).getValue("42501445")
        assertEquals(0, part.position)
        assertEquals(
            "Дорога в небольшом поселении на пару сотен человек серая, каменистая и неприятная.\nНо еще неприятнее была боль, создавшая в горле ком.\n\n— Доброе утро, — сказал он.",
            part.content,
        )

        capture(context, "https://ficbook.net/readfic/$work/comments", "comments.html")
        val comments = context.reviews.getValue(work)
        assertEquals(setOf("138019862", "138000001"), comments.keys)
        // A quote of another comment is not this one's words
        assertEquals("Спасибо-спасибо-спасибо! Я очень рада, что вам понравилось) Приятного чтения!", comments.getValue("138019862").content)
        assertEquals(Instant.parse("2026-09-01T09:15:00Z"), comments.getValue("138019862").postedAt)
    }
}
