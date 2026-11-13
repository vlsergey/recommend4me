package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.i18n.Translations
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jsoup.Jsoup

/** The extension finds on the site's pages what the application knows of them; the source has its words. */
class FfnDecorTest {

    private val source = Ffn()
    private val decor = source.pageDecor

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `a work's page has the place of the panel and of the lines of the facets`() {
        listOf("story.html", "crossover.html").forEach { name ->
            val doc = Jsoup.parse(page(name), "https://www.fanfiction.net/s/14597463/2/")
            assertEquals(1, doc.select(decor.panelAfter!!).size, name)
            decor.facets.forEach { assertEquals(1, doc.select(it.after!!).size, "$name: ${it.after}") }
        }
    }

    @Test
    fun `every card of a list and of an author's page links to its work, a list to its next page`() {
        mapOf(
                "list.html" to "https://www.fanfiction.net/book/Harry-Potter/",
                "author.html" to "https://www.fanfiction.net/u/16441913/",
            )
            .forEach { (name, url) ->
                val captured = source.capture(CapturedPage(url, page(name), Instant.now()), MemorySourceContext())
                val doc = Jsoup.parse(page(name), url)
                val cards = decor.cards!!
                val linked =
                    doc.select(cards.selector)
                        .mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }
                        .mapNotNull(source::itemIdOf)
                assertEquals(captured, linked, name)
            }
        val list = Jsoup.parse(page("list.html"), "https://www.fanfiction.net/book/Harry-Potter/")
        assertEquals(
            listOf("https://www.fanfiction.net/book/Harry-Potter/?&srt=1&r=103&p=2"),
            list.select(decor.nextPage!!).map { it.absUrl("href") }.distinct(),
        )
    }

    @Test
    fun `the words of the source are complete`() {
        assertEquals(emptyList(), Translations.problems(source))
    }
}
