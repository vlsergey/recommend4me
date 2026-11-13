package io.github.vlsergey.recommend4me.source.ffn

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

/**
 * The pages of the site read. Their prose — summaries, chapters, reviews — is Lorem ipsum in the pages kept for the
 * tests, the authors' and reviewers' names neutral, every list shortened: the structure is the site's, the words are
 * not.
 */
class FfnPagesTest {

    private val source = Ffn()
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `the cards of a fandom's list give the works, their fandom of the list's own line`() {
        val context = MemorySourceContext()
        val read = capture(context, "https://www.fanfiction.net/book/Harry-Potter/", "list.html")
        assertEquals(6, read.size)
        val id = "14580148"
        assertEquals(id, read.first())
        val head = context.heads.getValue(id)
        assertEquals("Harry Potter: I Became Harry's Uncle", head.title)
        assertEquals("https://www.fanfiction.net/s/14580148/1/", head.url)
        assertEquals(Instant.ofEpochSecond(1794596988), head.updatedAt)
        val facets = context.facets.getValue(id)
        assertEquals(listOf("16441913"), facets["author"])
        assertEquals(listOf("Books"), facets["category"])
        assertEquals(listOf("Harry Potter"), facets["fandom"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertEquals(listOf(AgeRating.G.key), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("en"), facets["language"])
        assertEquals(listOf("mystery", "adventure"), facets["tag"])
        assertEquals(listOf("harry p."), facets["pairing"])
        assertEquals("Harry P.", context.texts.getValue(id)["pairingsLine"])
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), facets["status"])
        val numbers = context.numbers.getValue(id)
        assertEquals(134.0, numbers["parts"])
        assertEquals(231130.0, numbers["words"])
        assertEquals(75.0, numbers["reviews"])
        assertEquals(147.0, numbers["favs"])
        assertEquals(166.0, numbers["follows"])
        assertTrue(context.texts.getValue(id)["annotation"].orEmpty().startsWith("Lorem ipsum"))
        assertEquals(listOf("https://www.fanfiction.net/image/7575675/180/"), context.pictures.getValue(id))
        // Another language; the site's picture of no cover is none
        assertEquals(listOf("es"), context.facets.getValue("14600657")["language"])
        assertEquals(emptyList(), context.pictures.getValue("14594361"))
    }

    @Test
    fun `a work's page gives its header, its line, every chapter named and the text of the one it shows`() {
        val context = MemorySourceContext()
        val id = "14597463"
        assertEquals(
            listOf(id),
            capture(context, "https://www.fanfiction.net/s/14597463/2/Time-After-Time", "story.html"),
        )
        assertEquals("Time After Time", context.heads.getValue(id).title)
        assertEquals(Instant.ofEpochSecond(1794591415), context.heads.getValue(id).updatedAt)
        val facets = context.facets.getValue(id)
        assertEquals(listOf("2996195"), facets["author"])
        assertEquals(listOf("Books"), facets["category"])
        assertEquals(listOf("Harry Potter"), facets["fandom"])
        assertEquals(listOf(AgeRating.PG13.key), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("suspense", "romance"), facets["tag"])
        assertEquals(listOf("hermione g./sirius b.", "harry p./luna l."), facets["pairing"])
        assertEquals("[Hermione G., Sirius B.] [Harry P., Luna L.]", context.texts.getValue(id)["pairingsLine"])
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        assertEquals(24006.0, context.numbers.getValue(id)["words"])
        assertEquals(7.0, context.numbers.getValue(id)["reviews"])
        val parts = context.parts.getValue(id).values.sortedBy { it.position }
        assertEquals(listOf("1", "2", "3"), parts.map { it.id })
        assertEquals(listOf("A Different Past", "A Different Life", "A Different Destiny"), parts.map { it.title })
        assertNull(parts[0].content)
        assertTrue(parts[1].content.orEmpty().startsWith("Lorem ipsum"))
        assertEquals(emptyList(), context.pictures.getValue(id))
    }

    @Test
    fun `a crossover's page names its two fandoms, its cover the largest`() {
        val context = MemorySourceContext()
        val id = "14589353"
        assertEquals(listOf(id), capture(context, "https://www.fanfiction.net/s/14589353/1/", "crossover.html"))
        val facets = context.facets.getValue(id)
        assertEquals(listOf("Harry Potter", "Pirates of the Caribbean"), facets["fandom"])
        assertNull(facets["category"])
        // No characters named: none
        assertEquals(emptyList(), facets["pairing"])
        assertEquals(listOf("https://www.fanfiction.net/image/7587904/180/"), context.pictures.getValue(id))
        assertEquals(4, context.parts.getValue(id).size)
    }

    @Test
    fun `the reviews of a work known are kept, each by the site's own id`() {
        val context = MemorySourceContext()
        val url = "https://www.fanfiction.net/r/14597463/"
        assertEquals(emptyList(), capture(context, url, "reviews.html"))
        capture(context, "https://www.fanfiction.net/s/14597463/2/Time-After-Time", "story.html")
        assertEquals(listOf("14597463"), capture(context, url, "reviews.html"))
        val reviews = context.reviews.getValue("14597463")
        assertEquals(8, reviews.size)
        val first = reviews.getValue("300716887")
        assertEquals("Reader 1", first.author)
        assertEquals(Instant.ofEpochSecond(1794595770), first.postedAt)
        assertTrue(first.content.startsWith("Lorem ipsum"))
        // A guest's name is no link
        assertEquals("Reader 5", reviews.getValue("300668998").author)
    }

    @Test
    fun `an author's page gives the works they wrote, a crossover's fandoms of its line`() {
        val context = MemorySourceContext()
        val read = capture(context, "https://www.fanfiction.net/u/16441913/", "author.html")
        assertEquals(listOf("14589353", "14580148", "14586194"), read)
        val crossover = context.facets.getValue("14589353")
        assertEquals(listOf("16441913"), crossover["author"])
        assertEquals("Author 1", context.facetNames.getValue("author")["16441913"])
        assertEquals(listOf("Harry Potter", "Pirates of the Caribbean"), crossover["fandom"])
        assertEquals(listOf("Frieren: Beyond Journey's End"), context.facets.getValue("14586194")["fandom"])
    }

    @Test
    fun `the line of the site's template is read by its captions and order`() {
        val line =
            FfnLine.of(
                "Rated: T - English - Romance/Hurt/Comfort - Chapters: 33 - Words: 124,600 - Reviews: 201 - " +
                    "Favs: 188 - Follows: 276 - Updated: 5h ago - Published: Oct 8 - " +
                    "[Harry P., Daphne G.] Poppy P. - Complete"
            )
        assertEquals("T", line.rating)
        assertEquals(listOf("Romance", "Hurt/Comfort"), line.genres)
        assertEquals("[Harry P., Daphne G.] Poppy P.", line.characters)
        assertEquals(listOf("Harry P./Daphne G.", "Poppy P."), FfnLine.castOf(line.characters!!))
        assertTrue(line.complete)
        assertEquals(124600L, line.counts["Words"])
    }

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals("14597463", source.itemIdOf("https://www.fanfiction.net/s/14597463/2/Time-After-Time"))
        assertEquals("14597463", source.itemIdOf("https://www.fanfiction.net/r/14597463/0/2/"))
        assertNull(source.itemIdOf("https://www.fanfiction.net/book/Harry-Potter/"))
        assertTrue(
            source.capturePatterns.any { it.matches("https://www.fanfiction.net/Harry-Potter_and_X_Crossovers/1/2/") }
        )
        assertTrue(source.capturePatterns.none { it.matches("https://www.fanfiction.net/forums/book/Harry-Potter/") })
    }
}
