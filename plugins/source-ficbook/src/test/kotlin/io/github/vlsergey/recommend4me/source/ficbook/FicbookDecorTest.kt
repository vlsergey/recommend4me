package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

/** The extension finds on the site's pages what the application knows of them. */
class FicbookDecorTest {

    private val source = Ficbook()
    private val decor = source.pageDecor
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private val work = "019fc2ac-ce89-7afa-aab8-b51d74fc50f7"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals(work, source.siteIdOf("https://ficbook.net/readfic/$work"))
        assertEquals(work, source.siteIdOf("https://ficbook.net/readfic/$work/12345#part_content"))
        assertEquals(work, source.siteIdOf("https://ficbook.net/readfic/$work/comments"))
        assertEquals(work, source.siteIdOf("https://ficbook.net/readfic/$work?source=premium"))
        assertNull(source.siteIdOf("https://ficbook.net/fanfiction/no_fandom/originals"))
    }

    @Test
    fun `a work's page shows its tags, its cover and the place of the panel`() {
        val html = page("readfic.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://ficbook.net/readfic/$work", html, now), context)
        val doc = Jsoup.parse(html, "https://ficbook.net/readfic/$work")
        assertTrue(doc.select(decor.panelAfter!!).isNotEmpty())
        // The site's tags are its word, left alone; the work's tags go in one line right after them
        assertTrue(decor.facets.none { it.facet == Ficbook.TAG })
        val tags = decor.facets.single { it.facet == Books.TAGS.key }
        // A tag by the id of its page
        val shown = doc.select(tags.after!!).select("a.tag").map { it.attr("href").removePrefix("/tags/") }
        assertEquals(context.facets.getValue(work).getValue(Ficbook.TAG).sorted(), shown.sorted())
        // The worked out tags, pairings and characters go under the site's blocks of them
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
        val captured =
            context.sitesOf(
                source.capture(CapturedPage("https://ficbook.net/fanfiction/no_fandom/originals", html, now), context)
            )
        val doc = Jsoup.parse(html, "https://ficbook.net/fanfiction/no_fandom/originals")
        val cards = decor.cards!!
        val linked =
            doc.select(cards.selector)
                .mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }
                .mapNotNull(source::siteIdOf)
        assertTrue(linked.containsAll(captured), "$linked hold $captured")
    }

    @Test
    fun `an author's own page tells the author`() {
        assertEquals("1000001", source.authorOf("https://ficbook.net/authors/1000001"))
        val uuid = "0189dad5-3b41-7037-905e-a4d06d778b07"
        assertEquals(uuid, source.authorOf("https://ficbook.net/authors/$uuid"))
        assertEquals(null, source.authorOf("https://ficbook.net/readfic/1"))
    }

    @Test
    fun `an author's page of the older kind, by the account's name, is the author known by the name`() {
        assertEquals("name:Мышилла", source.authorOf("https://ficbook.net/authors/Мышилла"))
        assertEquals("name:Свежий ветер", source.authorOf("http://ficbook.net/authors/Свежий ветер"))
        val encoded = "%D0%9C%D1%8B%D1%88%D0%B8%D0%BB%D0%BB%D0%B0"
        assertEquals("name:Мышилла", source.authorOf("https://ficbook.net/authors/$encoded"))
        assertEquals("name:A+B", source.authorOf("https://ficbook.net/authors/A+B"))
    }
}
