package io.github.vlsergey.recommend4me.source.aff

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
 * The pages of the site read. Their prose — descriptions, chapters, disclaimers, reviews — is Lorem ipsum in the pages
 * kept for the tests, and the reviewers' names neutral: the structure is the site's, the words are not.
 */
class AffPagesTest {

    private val source = Aff()
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    /** A work of five chapters in the first page of the Harry Potter archive. */
    private val adversary = "hp-600100589"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, html: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, html, now), context)
    }

    private fun list(context: MemorySourceContext, html: String = page("list.html")) =
        capture(context, "https://hp.adult-fanfiction.org/", html)

    @Test
    fun `the cards of an archive's list give the works whole`() {
        val context = MemorySourceContext()
        val read = list(context)
        assertEquals(18, read.size)
        assertEquals(adversary, read.first())
        val head = context.heads.getValue(adversary)
        assertEquals("Adversary", head.title)
        assertEquals("https://hp.adult-fanfiction.org/story.php?no=600100589", head.url)
        assertEquals(Instant.parse("2026-11-10T00:00:00Z"), head.updatedAt)
        val facets = context.facets.getValue(adversary)
        assertEquals(listOf("1296842027"), facets["author"])
        assertEquals(listOf("hp"), facets["archive"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertEquals(listOf("hp/Harry Potter"), facets["fandom"])
        assertEquals("Harry Potter", context.facetNames.getValue("fandom")["hp/Harry Potter"])
        // A fandom's page is its archive's; a key of no archive has none
        assertEquals("https://hp.adult-fanfiction.org/", source.valueUrl("fandom", "hp/Harry Potter", "Harry Potter"))
        assertNull(source.valueUrl("fandom", "Harry Potter", "Harry Potter"))
        assertEquals(listOf("harry potter » het - male/female » hermione/voldemort"), facets["category"])
        assertEquals(listOf("hermione/voldemort"), facets["pairing"])
        assertEquals("Pairing: Hermione/Voldemort", context.texts.getValue(adversary)["pairingsLine"])
        // The node of the category and the code "MF" tell the same
        assertEquals(listOf(Books.Direction.HET.key), facets["direction"])
        assertEquals(listOf(AgeRating.NC17.key), facets[AgeRating.SITE_KEY])
        // "COMPLETE" is the status, no tag; a code is a tag told in full
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        val tags = facets["tag"].orEmpty()
        assertTrue("complete" !in tags && "bdsm" in tags && "mf" in tags, "$tags")
        // Named by the short name the site tells a code by, told in full by its description
        assertEquals("M/F", context.facetNames.getValue("tag")["mf"])
        assertEquals("BDSM", context.facetNames.getValue("tag")["bdsm"])
        assertEquals("M/F - Het", context.facetDescriptions.getValue("tag")["mf"])
        assertEquals(5.0, context.numbers.getValue(adversary)["parts"])
        assertEquals(431.0, context.numbers.getValue(adversary)["views"])
        assertTrue(context.texts.getValue(adversary)["annotation"].orEmpty().startsWith("Lorem ipsum"))
    }

    @Test
    fun `a work's pages give its header, every chapter named and the text of the one each shows`() {
        val context = MemorySourceContext()
        // A work never seen by its card is not read of its page: what it is about is on its card alone
        assertEquals(emptyList(), capture(context, source.itemUrl(adversary), page("story.html")))
        list(context)
        assertEquals(listOf(adversary), capture(context, source.itemUrl(adversary), page("story.html")))
        assertEquals(listOf(adversary), capture(context, source.partUrl(adversary, "2"), page("chapter.html")))
        val parts = context.parts.getValue(adversary)
        assertEquals(listOf("1", "2", "3", "4", "5"), parts.values.sortedBy { it.position }.map { it.id })
        assertEquals("Unimportant", parts.getValue("1").title)
        assertEquals("Grand Entrance", parts.getValue("2").title)
        assertTrue(parts.getValue("1").content.orEmpty().startsWith("Lorem ipsum"))
        assertTrue(parts.getValue("2").content.orEmpty().startsWith("Lorem ipsum"))
        assertNull(parts.getValue("3").content)
        assertEquals(listOf(AgeRating.NC17.key), context.facets.getValue(adversary)[AgeRating.SITE_KEY])
    }

    @Test
    fun `a work's page tells its place in the archive's tree, its fandom of it, as a card of the archive does`() {
        val context = MemorySourceContext()
        // Known by a card that tells no whole category: an author's
        context.items.upsert(ItemHead(adversary, source.itemUrl(adversary), "Adversary"), now)
        assertEquals(listOf(adversary), capture(context, source.itemUrl(adversary), page("story.html")))
        val facets = context.facets.getValue(adversary)
        assertEquals(listOf("harry potter » het - male/female » hermione/voldemort"), facets["category"])
        assertEquals(listOf("hp/Harry Potter"), facets["fandom"])
        assertEquals(listOf("hermione/voldemort"), facets["pairing"])
        assertEquals(listOf(Books.Direction.HET.key), facets["direction"])
    }

    @Test
    fun `the reviews of a work known are kept, each by its reviewer and time`() {
        val context = MemorySourceContext()
        list(context)
        val love = "hp-600100280"
        assertEquals(
            listOf(love),
            capture(context, "https://hp.adult-fanfiction.org/reviews.php?no=600100280", page("reviews.html")),
        )
        val reviews = context.reviews.getValue(love).values
        assertEquals(10, reviews.size)
        val first = reviews.first { it.author == "Reader 1" }
        assertEquals(Instant.parse("2023-04-20T00:00:00Z"), first.postedAt)
        assertTrue(first.content.startsWith("Lorem ipsum"))
    }

    @Test
    fun `an author's page gives the works they wrote, its cards of another markup`() {
        val context = MemorySourceContext()
        val url = "https://members.adult-fanfiction.org/profile.php?id=1296995621&tab=stories-written&a=games"
        val read = capture(context, url, page("profile.html"))
        assertEquals(6, read.size)
        val raider = "games-600091826"
        assertEquals(raider, read.first())
        assertEquals("BDSM Raider", context.heads.getValue(raider).title)
        assertEquals(Instant.parse("2026-03-12T00:00:00Z"), context.heads.getValue(raider).updatedAt)
        val facets = context.facets.getValue(raider)
        assertEquals(listOf("1296995621"), facets["author"])
        assertEquals("CrossroadsMk2", context.facetNames.getValue("author")["1296995621"])
        assertEquals(listOf("games"), facets["archive"])
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        assertTrue("bdsm" in facets["tag"].orEmpty())
        // Its category by its last node alone: not written, the archive's card tells the whole of it
        assertNull(facets["category"])
        assertEquals(3.0, context.numbers.getValue(raider)["parts"])
        assertEquals(22372.0, context.numbers.getValue(raider)["views"])
        assertTrue(context.texts.getValue(raider)["annotation"].orEmpty().startsWith("Lorem ipsum"))
    }

    @Test
    fun `the site's legend names and describes every code it tells, one of no work and of no legend kept`() {
        // The filters of the advanced search as the site writes them: a title "Short - what it means" with the code
        val filter = { code: String, title: String ->
            """<div class="tag-filter-item"><label class="tag-filter-checkbox" title="$title">""" +
                """<input type="checkbox" name="tags[]" value="$code"><span>$code</span></label></div>"""
        }
        val html =
            "<html><body><form>" +
                filter("Angst", "Angst - Torment, anguish") +
                filter("Lorem", " Lorem - Ipsum dolor sit amet") +
                filter("WIP", "WIP - Work in progress") +
                "</form></body></html>"
        val context = MemorySourceContext()
        assertEquals(emptyList(), capture(context, "https://hp.adult-fanfiction.org/advancedsearch.php", html))
        // A code the code's own legend has not is named and described all the same; the status is no tag
        assertEquals(mapOf("angst" to "Angst", "lorem" to "Lorem"), context.facetNames.getValue("tag"))
        assertEquals("Lorem - Ipsum dolor sit amet", context.facetDescriptions.getValue("tag")["lorem"])
        assertTrue("wip" !in context.facetDescriptions.getValue("tag"))
    }

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals(adversary, source.itemIdOf("https://hp.adult-fanfiction.org/story.php?no=600100589"))
        assertEquals(adversary, source.itemIdOf("https://hp.adult-fanfiction.org/story.php?no=600100589&chapter=3"))
        assertEquals(adversary, source.itemIdOf("https://hp.adult-fanfiction.org/reviews.php?no=600100589&page=2"))
        assertEquals("anime2-24686", source.itemIdOf("https://anime2.adult-fanfiction.org/story.php?no=24686"))
        assertNull(source.itemIdOf("https://hp.adult-fanfiction.org/index.php?cat=3"))
        assertNull(source.itemIdOf("https://www.adult-fanfiction.org/"))
        assertTrue(source.capturePatterns.none { it.matches("https://www.adult-fanfiction.org/") })
    }
}
