package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class Ao3PagesTest {

    private val source = Ao3()
    private val now = Instant.parse("2026-10-13T10:00:00Z")

    /** The work of four chapters of seven the pages of a work show. */
    private val saints = "93948516"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return context.sitesOf(source.capture(CapturedPage(url, page(name), now), context))
    }

    @Test
    fun `the cards of a tag's list give the works with their header`() {
        val context = MemorySourceContext()
        val url = "https://archiveofourown.org/tags/Fullmetal%20Alchemist%20-%20All%20Media%20Types/works"
        assertEquals(listOf("94578656", "93589006", "74280956"), capture(context, url, "list.html"))
        val ishvalan = "74280956"
        assertEquals("https://archiveofourown.org/works/$ishvalan", context.heads.getValue(ishvalan).url)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), context.heads.getValue(ishvalan).updatedAt)
        val facets = context.facets.getValue(ishvalan)
        assertEquals(listOf("Wolfgang555"), facets["author"])
        assertEquals(listOf("nc-17"), facets[AgeRating.SITE_KEY])
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        assertEquals(listOf("en"), facets["language"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertTrue(facets["fandom"].orEmpty().isNotEmpty())
        assertEquals(37.0, context.numbers.getValue(ishvalan)["parts"])
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), context.facets.getValue("94578656")["status"])
        assertTrue(context.texts.getValue("94578656")["annotation"].orEmpty().isNotBlank())
        // A card's link is what the extension marks the card up by
        assertEquals(ishvalan, source.siteIdOf("https://archiveofourown.org/works/$ishvalan"))
    }

    @Test
    fun `a whole work's page gives its header and every chapter's text`() {
        val context = MemorySourceContext()
        assertEquals(
            listOf(saints),
            capture(context, "https://archiveofourown.org/works/$saints?view_full_work=true", "full.html"),
        )
        val head = context.heads.getValue(saints)
        assertEquals("We're Not Saints", head.title)
        val facets = context.facets.getValue(saints)
        // Anonymous: no author named
        assertNull(facets["author"])
        assertEquals(listOf("pg-13"), facets[AgeRating.KEY])
        assertEquals(listOf("gen"), facets["category"])
        // The warnings are tags of the site, before the others
        assertNull(facets["warning"])
        assertEquals("creator chose not to use archive warnings", facets["tag"].orEmpty().first())
        assertEquals(
            listOf("Fullmetal Alchemist - All Media Types", "Fullmetal Alchemist: Brotherhood & Manga"),
            facets["fandom"],
        )
        // A relationship is one whatever the order of its names; a character is itself
        val pairings = facets["pairing"].orEmpty()
        assertTrue("edward elric & ling yao" in pairings && "edward elric & roy mustang" in pairings, "$pairings")
        assertTrue("roy mustang" in pairings, "$pairings")
        assertTrue("angst" in facets["tag"].orEmpty())
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), facets["status"])
        val numbers = context.numbers.getValue(saints)
        assertEquals(4.0, numbers["parts"])
        assertTrue(numbers.getValue("words") > 6000, "$numbers")
        val texts = context.texts.getValue(saints)
        assertTrue(texts.getValue("annotation").startsWith("Ling Yao has been stuck in a basement"))
        assertTrue(texts.getValue("notes").startsWith("Heed the tags"))
        assertTrue(
            texts.getValue("pairingsLine").startsWith("Relationships: Edward Elric & Ling Yao"),
            texts.getValue("pairingsLine"),
        )
        val parts = context.parts.getValue(saints)
        assertEquals(4, parts.values.count { it.content != null }, "${parts.keys}")
        val first = parts.getValue("250718281")
        assertEquals("Argument", first.title)
        assertEquals(0, first.position)
        assertTrue(first.content!!.startsWith("Ling sat in a chair"), first.content!!.take(80))
    }

    @Test
    fun `a chapter's page gives its text, the others named by the menu of chapters`() {
        val context = MemorySourceContext()
        capture(context, "https://archiveofourown.org/works/$saints/chapters/250718281", "work.html")
        val parts = context.parts.getValue(saints)
        assertEquals(setOf("250718281", "251408676", "251792311", "252456561"), parts.keys)
        assertTrue(parts.getValue("250718281").content != null)
        assertNull(parts.getValue("251408676").content)
        assertEquals("Nothing", parts.getValue("251408676").title)
        // The table of contents adds the day every chapter came out
        capture(context, "https://archiveofourown.org/works/$saints/navigate", "navigate.html")
        assertEquals(
            Instant.parse("2026-10-08T00:00:00Z"),
            context.parts.getValue(saints).getValue("251408676").publishedAt,
        )
    }

    @Test
    fun `a work of one chapter keeps its text as its only part`() {
        val context = MemorySourceContext()
        assertEquals(listOf("94558111"), capture(context, "https://archiveofourown.org/works/94558111", "oneshot.html"))
        val parts = context.parts.getValue("94558111")
        assertEquals(setOf(Ao3.ONLY_PART), parts.keys)
        assertTrue(parts.getValue(Ao3.ONLY_PART).content.orEmpty().isNotBlank())
        assertEquals(listOf(Books.Status.FINISHED.key), context.facets.getValue("94558111")["status"])
    }

    @Test
    fun `the comments shown are the readers' opinions of the work`() {
        val context = MemorySourceContext()
        capture(
            context,
            "https://archiveofourown.org/works/89821571/chapters/238641631?show_comments=true&view_adult=true",
            "comments.html",
        )
        val reviews = context.reviews.getValue("89821571")
        val first = reviews.getValue("1278250446")
        assertEquals("Vestrais", first.author)
        assertTrue(first.content.startsWith("Ah GreedLing"), first.content)
        assertEquals(Instant.parse("2026-08-03T22:41:00Z"), first.postedAt)
        assertTrue(reviews.size > 1)
        // Its series, and its number there: "Part 6 of"
        assertEquals(listOf("5585796"), context.facets.getValue("89821571")["series"])
        assertEquals(6, context.seriesPlaces.getValue("5585796")["89821571"])
    }

    @Test
    fun `a series' page gives its title, dates, description, whether it is complete, and its works numbered`() {
        val context = MemorySourceContext()
        // The markup of a series' page as the archive writes it; the archive answered 418 when it was to be captured
        val html =
            """
            <html><body><div id="main" class="series-show">
            <h2 class="heading">Magic is Born From Desire</h2>
            <div class="wrapper"><dl class="series meta group">
            <dt>Creator:</dt><dd><a rel="author" href="/users/someone/pseuds/someone">someone</a></dd>
            <dt>Series Begun:</dt><dd>2024-03-01</dd>
            <dt>Series Updated:</dt><dd>2026-08-02</dd>
            <dt>Description:</dt><dd><blockquote class="userstuff"><p>Magic, and what it wants.</p></blockquote></dd>
            <dt>Stats:</dt><dd><dl class="stats"><dt>Words:</dt><dd>120,000</dd><dt>Works:</dt><dd>2</dd>
            <dt>Complete:</dt><dd>No</dd></dl></dd>
            </dl></div>
            <ul class="series work index group">
            <li id="work_85351946" class="work blurb group"><div class="header module"><h4 class="heading">
            <a href="/works/85351946">Fifth</a></h4></div>
            <ul class="series"><li>Part <strong>5</strong> of <a href="/series/5585796">Magic is Born From Desire</a></li></ul></li>
            <li id="work_89821571" class="work blurb group"><div class="header module"><h4 class="heading">
            <a href="/works/89821571">Sixth</a></h4></div>
            <ul class="series"><li>Part <strong>6</strong> of <a href="/series/5585796">Magic is Born From Desire</a></li></ul></li>
            </ul>
            </div></body></html>
            """
                .trimIndent()
        val url = "https://archiveofourown.org/series/5585796"
        assertTrue(source.capturePatterns.any { it.matches(url) })
        source.capture(CapturedPage(url, html, Instant.parse("2026-11-09T00:00:00Z")), context)
        val series = context.series.getValue("5585796")
        assertEquals("Magic is Born From Desire", series.title)
        assertEquals("in-progress", series.status)
        assertEquals("Magic, and what it wants.", series.description)
        assertEquals(Instant.parse("2024-03-01T00:00:00Z"), series.begunAt)
        assertEquals(
            listOf("85351946" to 5, "89821571" to 6),
            series.works.map { context.siteOf(it.itemId) to it.position },
        )
        assertTrue(!series.whole)
    }

    @Test
    fun `the warning before an adult work and a page of no work give nothing`() {
        val context = MemorySourceContext()
        val warning =
            """
            <html><body><div id="main" class="works-show">
            <p class="caution">This work could have adult content. If you continue, you have agreed that you are willing to see such content.</p>
            <ul class="actions"><li><a href="/works/74280956?view_adult=true">Yes, Continue</a></li></ul>
            </div></body></html>
            """
                .trimIndent()
        assertEquals(
            emptyList(),
            source.capture(CapturedPage("https://archiveofourown.org/works/74280956", warning, now), context),
        )
        assertTrue(context.heads.isEmpty())
    }

    @Test
    fun `the user's own marks are read of a page shown to them alone`() {
        val context = MemorySourceContext()
        // The whole work as a logged-in user sees it: kudos left, bookmarked, subscribed, marked for later
        val html =
            page("full.html")
                .replace(
                    "<header id=\"header\"",
                    "<p id=\"greeting\"><a href=\"/users/Reader\">Hi, Reader!</a></p><header id=\"header\"",
                )
                .replace("<p class=\"kudos\">", "<p class=\"kudos\"><a href=\"/users/Reader\">Reader</a>, ")
                .replace(
                    "<ul class=\"work navigation actions\">",
                    "<ul class=\"work navigation actions\">" +
                        "<li class=\"bookmark\"><a href=\"#bookmark-form\">Edit Bookmark</a></li>" +
                        "<li class=\"subscribe\"><form><input type=\"submit\" value=\"Unsubscribe\"></form></li>" +
                        "<li class=\"mark\"><a href=\"#\">Mark as Read</a></li>",
                )
        source.capture(
            CapturedPage("https://archiveofourown.org/works/$saints?view_full_work=true", html, now),
            context,
        )
        assertEquals(Books.YES.key, context.signalValues[saints to Books.LIKED.key])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_BOOKMARKED])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_SUBSCRIBED])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_LATER])
        // To anyone else the page tells nothing of them
        val other = MemorySourceContext()
        source.capture(
            CapturedPage("https://archiveofourown.org/works/$saints?view_full_work=true", page("full.html"), now),
            other,
        )
        assertTrue(other.signalValues.isEmpty())
    }

    @Test
    fun `a fandom's tag gives its synonyms and the metatags it is under, the list of its works none`() {
        val context = MemorySourceContext()
        val mdzs = "魔道祖师 - 墨香铜臭 | Modao Zushi - Moxiang Tongxiu"
        val url = "https://archiveofourown.org/tags/${Ao3Tags.path(mdzs)}"
        assertEquals(emptyList(), capture(context, url, "fandom.html"))
        assertEquals(mapOf(mdzs to ""), context.facetDescriptions["fandom"].orEmpty())
        assertEquals(
            listOf("Grandmaster of Demonic Cultivation", "Mo Dao Zu Shi", "魔道祖師").associateWith { mdzs },
            context.facetSynonyms["fandom"].orEmpty(),
        )
        // The metatag alone: not the category of the fandoms, "Books & Literature", nor its child tags
        assertEquals(mapOf(mdzs to setOf("$mdzs & Related Fandoms")), context.facetParents["fandom"].orEmpty())
        // The fandom asked of the browser by the address the page is read by
        assertEquals(listOf(url), source.describing("fandom", listOf(mdzs)).map { it.url })

        // The list of the fandom's works is no page of the tag
        val works = MemorySourceContext()
        source.capture(CapturedPage("$url/works", page("fandom.html"), now), works)
        assertTrue(works.facetSynonyms.isEmpty() && works.facetParents.isEmpty())
        // A tag of a category of no facet — a character — tells nothing
        val other = MemorySourceContext()
        val character = page("fandom.html").replace("the Fandom Category", "the Character Category")
        source.capture(CapturedPage(url, character, now), other)
        assertTrue(other.facetDescriptions.isEmpty() && other.facetSynonyms.isEmpty() && other.facetParents.isEmpty())
    }

    @Test
    fun `a tag's page gives its synonyms and the tags right above and under it, a synonym's page its tag`() {
        val context = MemorySourceContext()
        val url = "https://archiveofourown.org/tags/Angst"
        source.capture(
            CapturedPage(
                url,
                tagPage(
                    "Angst",
                    "Additional Tags",
                    """<div class="synonym listbox group"><h3 class="heading">Tags with the same meaning</h3>
                    <ul class="tags commas index group"><li><a class="tag" href="/tags/Sad">Sad</a></li></ul></div>
                    <div class="meta listbox group"><h3 class="heading">Metatags:</h3><ul class="tags tree index">
                    <li><a class="tag" href="/tags/Emotions">Emotions</a></li>
                    <li><ul class="tags tree index"><li><a class="tag" href="/tags/Feelings">Feelings</a></li></ul></li>
                    </ul></div>
                    <div class="sub listbox group"><h3 class="heading">Subtags:</h3><ul class='tags tree index'>
                    <li><a class="tag" href="/tags/Hurt*s*Comfort">Hurt/Comfort</a><ul class='tags tree index'>
                    <li><a class="tag" href="/tags/Whump">Whump</a></li></ul></li></ul></div>""",
                ),
                now,
            ),
            context,
        )
        // Keyed by their names in lower case, as the works give the tags; named as the site writes them
        assertEquals(mapOf("sad" to "angst"), context.facetSynonyms["tag"].orEmpty())
        // Its metatag right above it, its subtag right under it: those further are of the tags between
        assertEquals(
            mapOf("angst" to setOf("emotions"), "hurt/comfort" to setOf("angst")),
            context.facetParents["tag"].orEmpty(),
        )
        assertEquals("Hurt/Comfort", context.facetNames["tag"]?.get("hurt/comfort"))

        // A synonym's page: the tag it was merged into, besides the synonyms the tag's own page named
        source.capture(
            CapturedPage(
                "https://archiveofourown.org/tags/angsty",
                tagPage(
                    "angsty",
                    "Additional Tags",
                    """<div class="merger module"><h3 class="heading">Mergers</h3><p>angsty has been made a synonym of
                    <a class="tag" href="/tags/Angst">Angst</a>. Works and bookmarks tagged with angsty will show up in
                    Angst's filter.</p></div>""",
                ),
                now,
            ),
            context,
        )
        assertEquals(mapOf("sad" to "angst", "angsty" to "angst"), context.facetSynonyms["tag"].orEmpty())
        // A tag asked of the browser by its key: the site finds a tag by its name whatever the case
        assertEquals(
            listOf("https://archiveofourown.org/tags/angst"),
            source.describing("tag", listOf("angst")).map { it.url },
        )
    }

    /** A tag's page as the site makes it ("tags/show"): its heading, its category's line and its [lists]. */
    private fun tagPage(name: String, category: String, lists: String) =
        """<html><body><div id="main" class="tags-show region" role="main"><div class="tag home profile">
        <div class="primary header module"><h2 class="heading">$name</h2></div>
        <p>This tag belongs to the $category Category.</p>$lists</div></div></body></html>"""

    @Test
    fun `a summary whose text is not found is left as it was, a summary the work has not is none`() {
        val context = MemorySourceContext()
        val url = "https://archiveofourown.org/works/$saints?view_full_work=true"
        capture(context, url, "full.html")
        val summary = context.texts.getValue(saints)["annotation"]
        assertTrue(!summary.isNullOrBlank())

        val otherMarkup =
            Jsoup.parse(page("full.html")).also { it.select(".preface .summary blockquote").remove() }.outerHtml()
        source.capture(CapturedPage(url, otherMarkup, now), context)
        assertEquals(summary, context.texts.getValue(saints)["annotation"])

        val noSummary = Jsoup.parse(page("full.html")).also { it.select(".preface .summary").remove() }.outerHtml()
        source.capture(CapturedPage(url, noSummary, now), context)
        assertNull(context.texts.getValue(saints)["annotation"])
    }
}
