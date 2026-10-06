package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemsController
import io.github.vlsergey.recommend4me.page.PagesController
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.SuggestionsController
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The site as the application's interface: the extension's view of a work's page and of a list,
 * the suggested tags confirmed and rejected, a value added by its name, the works found for a link.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/site"])
class SiteTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun clean() {
            File("build/test-data/site").deleteRecursively()
        }
    }

    @TestConfiguration
    class Plugins {
        @Bean
        fun siteSource(): Source = SiteSource()

        @Bean
        fun wordsEncoder(): TextEncoder = WordsEncoder()
    }

    /** A site whose pages are "<id>|<title>|<tags>|<annotation>", with a tag to suggest. */
    class SiteSource : Source {
        override val id = "site"
        override val title = "Site"
        override val contentType = "books"
        override val homepage = "https://site.example"
        override val schema = SourceSchema(
            facets = listOf(FacetDef("tag", "Метка", suggest = true)),
            texts = listOf(TextDef("annotation", "Аннотация", block = "text:annotation")),
        )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://site\\.example/work/.*"))
        override fun itemUrl(itemId: String) = "https://site.example/work/$itemId"
        override fun itemIdOf(url: String) = Regex("^https://site\\.example/work/([^/?#]+)").find(url)?.groupValues?.get(1)
        override val pageDecor = PageDecor(
            panelAfter = "h1",
            facets = listOf(FacetDecor("tag", ".tags a")),
            cards = CardDecor(".card", "a.title"),
        )

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val (id, title, tags, annotation) = page.html.split('|')
            context.items.upsert(ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")), page.capturedAt)
            context.items.setFacet(id, "tag", tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it.replaceFirstChar(Char::uppercase)) })
            context.items.setTexts(id, mapOf("annotation" to annotation))
            return listOf(id)
        }
    }

    /** Texts as bags of their words, hashed into a few dimensions: alike words, alike vectors. */
    class WordsEncoder : TextEncoder {
        override val id = "words"
        override val dim = 64
        override fun ready() = true

        override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> = texts.map { text ->
            val v = FloatArray(dim)
            Regex("\\p{L}+").findAll(text.lowercase()).forEach { w -> v[Math.floorMod(w.value.take(5).hashCode(), dim)] += 1f }
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            if (n == 0f) null else FloatArray(dim) { v[it] / n }
        }

        override fun encodeWindows(texts: List<String>): List<List<FloatArray>> = encode(texts).map { listOfNotNull(it) }
    }

    @Autowired lateinit var captures: CaptureController
    @Autowired lateinit var items: ItemsController
    @Autowired lateinit var corrections: CorrectionsController
    @Autowired lateinit var pages: PagesController
    @Autowired lateinit var suggestionsApi: SuggestionsController
    @Autowired lateinit var stores: Stores
    @Autowired lateinit var textVectors: TextVectors
    @Autowired lateinit var suggestions: FacetSuggestions

    private fun capture(body: String) = captures.capturePage(CaptureRequest("https://site.example/work/${body.substringBefore('|')}", body))

    /** Forty works of two kinds, and one of the first kind the site gave no tags. */
    private fun catalogue() {
        repeat(20) { k ->
            capture("s$k|Space $k|space,ships|Starships cross the galaxy, pilots fight in orbit near distant planets $k")
            capture("d$k|Dragons $k|dragons,magic|A dragon guards the castle, wizards cast spells and magic $k")
        }
        capture("x1|Lost fleet|${""}|Pilots of starships lost beyond the galaxy fight near planets")
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store, null)
    }

    @Test
    fun `a work's page and a list show what the application knows, and the site's tags are corrected on them`() {
        catalogue()

        val page = pages.getPage("https://site.example/work/x1?from=list").body!!
        assertEquals("site", page.source)
        assertEquals("x1", page.itemId)
        assertEquals(".tags a", page.decor.facets.single().propertyValues)
        val item = assertNotNull(page.item)
        assertEquals(5, item.grades.size)

        // The tags of its kind are suggested for the untagged work
        val suggested = item.suggestions.single().suggested.map { it.key }
        assertTrue("space" in suggested && "ships" in suggested, "suggested $suggested")
        assertTrue("dragons" !in suggested, "suggested $suggested")

        // Confirmed, it is the work's; rejected, it is no longer suggested
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, key = "space"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = false, key = "ships"))
        val after = suggestionsApi.getSuggestions("site", "x1").body!!.single()
        assertTrue(after.suggested.none { it.key == "space" || it.key == "ships" }, "after the answers ${after.suggested}")
        val facets = pages.getPage("https://site.example/work/x1").body!!.item!!.facets.single().propertyValues
        assertEquals(FacetValueInfo.Corrected.ADDED, facets.single { it.key == "space" }.corrected)

        // A value added by its name finds the site's key, any case; a new one gets its name in lower case
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, name = "MAGIC"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, name = "Космос"))
        val values = items.getItem("site", "x1").body!!.allFacets.single().propertyValues
        assertEquals(setOf("space", "magic", "космос"), values.filter { it.corrected == FacetValueInfo.Corrected.ADDED }.map { it.key }.toSet())
        assertEquals("Космос", values.single { it.key == "космос" }.name)
        assertEquals(listOf("magic"), items.listFacetValues("site", "tag", "mag", 10).body!!.map { it.key })

        // The cards of a list get the works' grades
        items.rateItem("site", "s1", RatingRequest(4))
        val cards = pages.getCards(CardsRequest("https://site.example/list", listOf("/work/s1", "/work/unknown", "https://elsewhere.example/work/s2"))).body!!
        assertEquals(listOf("/work/s1"), cards.map { it.link })
        assertEquals(4, cards.single().grade)

        // A work is found by a piece of its title and by the address of its page
        assertEquals(listOf("x1"), items.lookupItems("books", "lost fl", 10).body!!.map { it.item })
        assertEquals("d3", items.lookupItems("books", "https://site.example/work/d3", 10).body!!.first().item)

        // A page of no work, and a page of no source
        assertNull(pages.getPage("https://site.example/about").body!!.item)
        assertEquals(404, pages.getPage("https://nowhere.example/").statusCode.value())

        // An error says what is wrong
        val error = assertThrows<ResponseStatusException> { corrections.correctFacet("site", "x1", FacetCorrection(facet = "nope", added = true, key = "x")) }
        assertTrue(error.reason!!.contains("nope"))
    }
}
