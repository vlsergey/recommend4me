package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The extension finds on the site's pages what the application knows of them. */
class FanficsMeDecorTest {

    private val source = FanficsMe()
    private val decor = source.pageDecor
    private val now = Instant.parse("2026-10-28T15:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `a work's page shows the place of the panel, of the lines worked out and its cover`() {
        listOf("252474" to "fic.html", "251745" to "fic-translation.html").forEach { (work, name) ->
            val html = page(name)
            val context = MemorySourceContext()
            source.capture(CapturedPage("https://fanfics.me/fic$work", html, now), context)
            val doc = Jsoup.parse(html, "https://fanfics.me/fic$work")
            assertEquals(1, doc.select(decor.panelAfter!!).size, name)
            decor.facets.forEach { assertEquals(1, doc.select(it.after!!).size, "${it.after} on $name") }
            val cover = doc.select(decor.pictures!!.selector).map { it.absUrl("src") }
            assertEquals(context.pictures[work]?.filterNotNull() ?: emptyList(), cover, name)
        }
    }

    @Test
    fun `the comments of a page are the ones kept`() {
        val html = page("fic-comments.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://fanfics.me/fic226660", html, now), context)
        val r = decor.reviews!!
        val ids = Jsoup.parse(html).select(r.selector).map { it.attr(r.idAttribute).removePrefix(r.idPrefix) }
        assertTrue(ids.isNotEmpty())
        assertEquals(context.reviews.getValue("226660").keys, ids.toSet())
    }

    @Test
    fun `every card of a list links to its work`() {
        val html = page("find.html")
        val context = MemorySourceContext()
        val captured = source.capture(CapturedPage("https://fanfics.me/find", html, now), context)
        val doc = Jsoup.parse(html, "https://fanfics.me/find")
        val cards = decor.cards!!
        val linked = doc.select(cards.selector).mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }.mapNotNull(source::itemIdOf)
        assertEquals(captured, linked)
    }
}
