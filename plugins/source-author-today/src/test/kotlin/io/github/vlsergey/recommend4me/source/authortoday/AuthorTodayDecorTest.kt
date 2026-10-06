package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The extension finds on the site's pages what the application knows of them. */
class AuthorTodayDecorTest {

    private val source = AuthorToday()
    private val decor = source.pageDecor
    private val now = Instant.parse("2026-10-06T10:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals("216859", source.itemIdOf("https://author.today/work/216859"))
        assertEquals("216859", source.itemIdOf("https://author.today/work/216859/reviews"))
        assertEquals("216859", source.itemIdOf("https://author.today/reader/216859/1960452"))
        assertEquals("216859", source.itemIdOf("https://author.today/work/216859?utm=x"))
        assertNull(source.itemIdOf("https://author.today/work/genre/fantasy"))
        assertNull(source.itemIdOf("https://author.today/review/913603"))
    }

    @Test
    fun `a work's page shows its tags, its cover and the place of the panel`() {
        val html = page("work-216859.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://author.today/work/216859", html, now), context)
        val doc = Jsoup.parse(html, "https://author.today/work/216859")
        assertTrue(doc.select(decor.panelAfter!!).isNotEmpty())

        val tags = decor.facets.first { it.facet == AuthorToday.TAG }
        val shown = doc.select(tags.values).map { (it.attr("title").ifBlank { it.text() }).trim().lowercase() }
        assertEquals(context.facets.getValue("216859").getValue(AuthorToday.TAG).sorted(), shown.sorted())

        val genres = decor.facets.first { it.facet == AuthorToday.GENRE }
        val names = context.facetNames.getValue(AuthorToday.GENRE)
        val genreNames = context.facets.getValue("216859").getValue(AuthorToday.GENRE).map { names.getValue(it).lowercase() }
        val genreElements = doc.select(genres.values).map { it.text().trim().lowercase() }
        assertTrue(genreElements.containsAll(genreNames), "$genreElements hold $genreNames")

        val cover = doc.select(decor.pictures!!.selector).map { it.attr("src").substringBefore('?') }
        assertEquals(context.pictures.getValue("216859").filterNotNull(), cover)
    }

    @Test
    fun `the reviews of a page are the ones kept`() {
        val html = page("work-216859-reviews.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://author.today/work/216859", page("work-216859.html"), now), context)
        source.capture(CapturedPage("https://author.today/work/216859/reviews", html, now), context)
        val r = decor.reviews!!
        val ids = Jsoup.parse(html).select(r.selector).map { it.attr(r.idAttribute).removePrefix(r.idPrefix) }
        assertTrue(ids.isNotEmpty())
        assertEquals(context.reviews.getValue("216859").keys, ids.toSet())
    }

    @Test
    fun `every card of a list links to its work`() {
        val html = page("genre-fantasy.html")
        val context = MemorySourceContext()
        val captured = source.capture(CapturedPage("https://author.today/work/genre/fantasy", html, now), context)
        val doc = Jsoup.parse(html, "https://author.today/work/genre/fantasy")
        val cards = decor.cards!!
        val linked = doc.select(cards.selector).mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }.mapNotNull(source::itemIdOf)
        assertEquals(captured, linked)
    }
}
