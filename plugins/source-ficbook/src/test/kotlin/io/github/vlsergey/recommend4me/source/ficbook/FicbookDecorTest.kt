package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The extension finds on the site's pages what the application knows of them. */
class FicbookDecorTest {

    private val source = Ficbook()
    private val decor = source.pageDecor
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private val work = "019fc2ac-ce89-7afa-aab8-b51d74fc50f7"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals(work, source.itemIdOf("https://ficbook.net/readfic/$work"))
        assertEquals(work, source.itemIdOf("https://ficbook.net/readfic/$work/12345#part_content"))
        assertEquals(work, source.itemIdOf("https://ficbook.net/readfic/$work/comments"))
        assertEquals(work, source.itemIdOf("https://ficbook.net/readfic/$work?source=premium"))
        assertNull(source.itemIdOf("https://ficbook.net/fanfiction/no_fandom/originals"))
    }

    @Test
    fun `a work's page shows its tags, its cover and the place of the panel`() {
        val html = page("readfic.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://ficbook.net/readfic/$work", html, now), context)
        val doc = Jsoup.parse(html, "https://ficbook.net/readfic/$work")
        assertTrue(doc.select(decor.panelAfter!!).isNotEmpty())
        val tags = decor.facets.single { it.facet == Ficbook.TAG }
        val shown = doc.select(tags.values!!).map { it.text().trim().lowercase() }
        assertEquals(context.facets.getValue(work).getValue(Ficbook.TAG).sorted(), shown.sorted())
        // The worked out pairings and characters go under the site's block of them
        decor.facets.filter { it.after != null }.forEach { assertEquals(1, doc.select(it.after!!).size, it.after) }
        val cover = doc.select(decor.pictures!!.selector).map { it.attr("src") }
        assertEquals(context.pictures[work]?.filterNotNull() ?: cover, cover)
    }

    @Test
    fun `the comments of a page are the ones kept`() {
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://ficbook.net/readfic/$work", page("readfic.html"), now), context)
        val html = page("comments.html")
        source.capture(CapturedPage("https://ficbook.net/readfic/$work/comments", html, now), context)
        val r = decor.reviews!!
        val ids = Jsoup.parse(html).select(r.selector).map { it.attr(r.idAttribute).removePrefix(r.idPrefix) }
        assertTrue(ids.isNotEmpty())
        assertEquals(context.reviews.getValue(work).keys, ids.toSet())
    }

    @Test
    fun `every card of a list links to its work`() {
        val html = page("fandom-originals.html")
        val context = MemorySourceContext()
        val captured = source.capture(CapturedPage("https://ficbook.net/fanfiction/no_fandom/originals", html, now), context)
        val doc = Jsoup.parse(html, "https://ficbook.net/fanfiction/no_fandom/originals")
        val cards = decor.cards!!
        val linked = doc.select(cards.selector).mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }.mapNotNull(source::itemIdOf)
        assertTrue(linked.containsAll(captured), "$linked hold $captured")
    }
}
