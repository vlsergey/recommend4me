package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jsoup.Jsoup

/** The extension finds on the site's pages what the application knows of them. */
class AffDecorTest {

    private val source = Aff()
    private val decor = source.pageDecor

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `a work's page has the place of the panel and of the lines of tags`() {
        listOf("story.html", "chapter.html").forEach { name ->
            val doc = Jsoup.parse(page(name), "https://hp.adult-fanfiction.org/story.php?no=600100589")
            assertEquals(1, doc.select(decor.panelAfter!!).size, name)
            decor.facets.forEach { assertEquals(1, doc.select(it.after!!).size, "$name: ${it.after}") }
        }
    }

    @Test
    fun `every card of a list links to its work, and the list to its next page`() {
        val html = page("list.html")
        val url = "https://hp.adult-fanfiction.org/"
        val captured = source.capture(CapturedPage(url, html, Instant.now()), MemorySourceContext())
        val doc = Jsoup.parse(html, url)
        val cards = decor.cards!!
        val linked =
            doc.select(cards.selector)
                .mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }
                .mapNotNull(source::itemIdOf)
        assertEquals(captured, linked)
        assertEquals(
            listOf("https://hp.adult-fanfiction.org/index.php?&page=2"),
            doc.select(decor.nextPage!!).map { it.absUrl("href") }.distinct(),
        )
    }
}
