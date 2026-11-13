package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jsoup.Jsoup

/** The extension finds on the site's pages what the application knows of them. */
class MolweDecorTest {

    private val source = Molwe()
    private val decor = source.pageDecor

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `a work's page has the place of the panel, of the lines of the facets and its reviews`() {
        listOf("work.html", "original.html").forEach { name ->
            val doc = Jsoup.parse(page(name), "https://molwe.ru/fanfics/20627")
            assertEquals(1, doc.select(decor.panelAfter!!).size, name)
            decor.facets.forEach { assertEquals(1, doc.select(it.after!!).size, "$name: ${it.after}") }
            assertEquals("Пэйринг и персонажи:", doc.selectFirst(Molwe.PAIRINGS_BLOCK)!!.child(0).text(), name)
            assertEquals("Метки", doc.selectFirst(Molwe.TAGS_BLOCK)!!.child(0).text(), name)
        }
        val original = Jsoup.parse(page("original.html"), "https://molwe.ru/originals/20656")
        assertEquals(
            listOf("29162"),
            original.select(decor.reviews!!.selector).map { it.attr(decor.reviews!!.idAttribute) },
        )
    }

    @Test
    fun `every card of a list links to its work, in either view, and the list to its next page`() {
        listOf("list-tiles.html", "list-rows.html").forEach { name ->
            val url = "https://molwe.ru/fanfics?page=2"
            val captured = source.capture(CapturedPage(url, page(name), Instant.now()), MemorySourceContext())
            val doc = Jsoup.parse(page(name), url)
            assertEquals(captured, linked(doc), name)
            assertEquals(
                listOf("https://molwe.ru/fanfics?page=3"),
                doc.select(decor.nextPage!!).map { it.absUrl("href") },
                name,
            )
        }
    }

    @Test
    fun `every card of an author's page links to its work`() {
        val url = "https://molwe.ru/author/34129"
        val doc = Jsoup.parse(page("author.html"), url)
        assertEquals(listOf("20656"), linked(doc))
        assertEquals(emptyList(), doc.select(decor.nextPage!!).map { it.absUrl("href") })
    }

    private fun linked(doc: org.jsoup.nodes.Document): List<String> {
        val cards = decor.cards!!
        return doc.select(cards.selector)
            .mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }
            .mapNotNull(source::itemIdOf)
    }
}
