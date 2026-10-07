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
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.universe.UniverseCatalogue
import io.github.vlsergey.recommend4me.universe.UniverseCharacter
import io.github.vlsergey.recommend4me.universe.UniverseClass
import io.github.vlsergey.recommend4me.universe.UniverseEntry
import io.github.vlsergey.recommend4me.universe.UniversesController
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

        @Bean
        fun fakeCatalogue(): UniverseCatalogue = FakeCatalogue()
    }

    /** A catalogue of two universes of space opera, each with the same two characters and a station, a place. */
    class FakeCatalogue : UniverseCatalogue {
        override val id = "fake"
        override val title = "Fake"
        private val space = UniverseEntry("U1", "Space opera", "Ships and pilots", "https://fake.example/U1")
        private val fleet = UniverseEntry("U2", "Fleet", "More ships", "https://fake.example/U2")

        override fun findUniverses(name: String, languages: List<String>) = if ("space" in name.lowercase()) listOf(space) else emptyList()
        override fun universe(id: String, languages: List<String>) = listOf(space, fleet).firstOrNull { it.id == id }
        override fun characters(id: String, languages: List<String>) = listOf(
            UniverseCharacter("C1", listOf("Pilot", "The Pilot"), null, "https://fake.example/C1", listOf("K1")),
            UniverseCharacter("C2", listOf("Captain"), "Of the fleet", "https://fake.example/C2", listOf("K1")),
            UniverseCharacter("S1", listOf("Station"), "Where ships dock", "https://fake.example/S1", listOf("K2")),
        )

        override fun classes(ids: Collection<String>, languages: List<String>) =
            listOf(UniverseClass("K1", "person", true), UniverseClass("K2", "place", false)).filter { it.id in ids }
    }

    /**
     * A site whose pages are "<id>|<title>|<tags>|<annotation>|<pairings>": tags to suggest, and
     * pairings the application works out from the line the site writes them in.
     */
    class SiteSource : Source {
        override val id = "site"
        override val title = "Site"
        override val contentType = "books"
        override val homepage = "https://site.example"
        override val schema = SourceSchema(
            facets = listOf(
                FacetDef("tag", "Метка", suggest = true),
                FacetDef("pairing", "Пэйринг", infer = true, original = "pairings"),
            ),
            texts = listOf(
                TextDef("annotation", "Аннотация", block = "text:annotation"),
                TextDef("pairings", "Пэйринги на сайте", searchByMeaning = true),
            ),
            universeLine = "pairings",
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
            val (id, title, tags, annotation, pairings) = page.html.split('|')
            context.items.upsert(ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")), page.capturedAt)
            context.items.setFacet(id, "tag", tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it.replaceFirstChar(Char::uppercase)) })
            context.items.setFacet(id, "pairing", pairings.split(", ").filter { it.isNotBlank() }.map { FacetValue(it.lowercase(), it) })
            context.items.setTexts(id, mapOf("annotation" to annotation, "pairings" to pairings))
            return listOf(id)
        }
    }

    /** Texts as bags of their words, hashed into a few dimensions: alike words, alike vectors. */
    class WordsEncoder : TextEncoder {
        override val id = "words"
        override val dim = 512
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
    @Autowired lateinit var universes: UniversesController

    private fun capture(body: String) = captures.capturePage(CaptureRequest("https://site.example/work/${body.substringBefore('|')}", body))

    /**
     * Forty works of two kinds, one of the first kind the site gave a dragons' tag by mistake, and
     * one of the first kind it gave nothing.
     */
    private fun catalogue() {
        repeat(20) { k ->
            // As on a site: some works with one tag of their two, some untagged, some without pairings
            fun tags(one: String, two: String) = when {
                k % 5 == 0 -> ""
                k % 3 == 0 -> one
                else -> "$one,$two"
            }
            // And some crossovers: the other kind's pairing beside the own one
            fun pairing(own: String, other: String) = when {
                k % 4 == 0 -> ""
                k % 7 == 3 -> "$own, $other"
                else -> own
            }
            capture("s$k|Space $k|${tags("space", "ships")}|Starships cross the galaxy, pilots fight in orbit near distant planets $k|${pairing("Pilot/Captain", "Knight/Princess")}")
            capture("d$k|Dragons $k|${tags("dragons", "magic")}|A dragon guards the castle, wizards cast spells and magic $k|${pairing("Knight/Princess", "Pilot/Captain")}")
        }
        capture("w1|Wrong tag|space,ships,magic|Starships cross the galaxy, pilots fight in orbit near distant planets|Pilot/Captain")
        capture("x1|Lost fleet||Pilots of starships lost beyond the galaxy fight near planets|")
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store)
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
        // A facet the work has no values of comes too, named: the page adds values to it
        assertEquals("Вселенная", item.facets.single { it.facet == "universe" }.label)

        // The tags of its kind are suggested for the untagged work
        val suggested = item.suggestions.single { it.facet == "tag" }.suggested.map { it.key }
        assertTrue("space" in suggested, "suggested $suggested")
        assertTrue("dragons" !in suggested, "suggested $suggested")

        // The pairing of its kind is given to it at once, marked as the model's
        val pairing = item.facets.single { it.facet == "pairing" }.propertyValues.single()
        assertEquals("pilot/captain", pairing.key)
        assertEquals(true, pairing.inferred)
        assertTrue(pairing.chance!! > 0.5, "the chance ${pairing.chance}")

        // Every site's value comes with its chance; the mistaken one is less likely than the work's own kind
        val wrong = items.getItem("site", "w1").body!!.allFacets.single { it.facet == "tag" }.propertyValues
        assertTrue(wrong.all { it.chance != null }, "chances $wrong")
        assertTrue(wrong.single { it.key == "magic" }.chance!! < wrong.single { it.key == "space" }.chance!!, "magic on a space work: $wrong")

        // Confirmed, it is the work's; rejected, it is no longer suggested
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, key = "space"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = false, key = "ships"))
        val after = suggestionsApi.getSuggestions("site", "x1").body!!.single { it.facet == "tag" }
        assertTrue(after.suggested.none { it.key == "space" || it.key == "ships" }, "after the answers ${after.suggested}")
        val facets = pages.getPage("https://site.example/work/x1").body!!.item!!.facets.single { it.facet == "tag" }.propertyValues
        assertEquals(FacetValueInfo.Corrected.ADDED, facets.single { it.key == "space" }.corrected)

        // A site's value the user says the work has is confirmed
        corrections.correctFacet("site", "w1", FacetCorrection(facet = "tag", added = true, key = "ships"))
        val confirmed = items.getItem("site", "w1").body!!.allFacets.single { it.facet == "tag" }.propertyValues.single { it.key == "ships" }
        assertEquals(FacetValueInfo.Corrected.CONFIRMED, confirmed.corrected)

        // A value added by its name finds the site's key, any case; a new one gets its name in lower case
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, name = "MAGIC"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, name = "Космос"))
        val values = items.getItem("site", "x1").body!!.allFacets.single { it.facet == "tag" }.propertyValues
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

    @Test
    fun `a universe found by the button joins the dictionary with its characters, and works are linked to it`() {
        capture("u1|Lone pilot|space|A pilot alone in orbit|")
        assertEquals(listOf("U1"), universes.searchUniverses("books", "Space").body!!.map { it.universe })
        val added = universes.addUniverse("books", UniverseRef("fake", "U1")).body!!
        assertEquals("fake:U1", added.value)
        assertEquals(2, added.characters)
        assertEquals(true, universes.searchUniverses("books", "space").body!!.single().added)
        assertEquals(listOf("Pilot", "The Pilot"), universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "C1" }.names)

        // The station is said to be in the universe, but a place: shown, not kept as a character
        assertEquals(3, added.total)
        val station = universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }
        assertEquals(false, station.included)
        assertEquals(listOf("K2"), station.classes)
        val place = universes.listUniverseClasses("books", "fake", "U1").body!!.single { it.propertyClass == "K2" }
        assertEquals(Triple("place", false, 1), Triple(place.name, place.included, place.count))
        // The user keeps the places of the universe, then leaves this one out by itself, then takes both words back
        universes.chooseUniverseClass("books", "fake", "U1", "K2", Inclusion(true))
        assertEquals(3, universes.listUniverses("books").body!!.single { it.universe == "U1" }.characters)
        universes.chooseUniverseEntry("books", "fake", "U1", "S1", Inclusion(false))
        assertEquals(false, universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }.choice)
        assertEquals(2, universes.listUniverses("books").body!!.single { it.universe == "U1" }.characters)
        universes.resetUniverseEntry("books", "fake", "U1", "S1")
        universes.resetUniverseClass("books", "fake", "U1", "K2")
        // The words stay over a refresh of the universe
        universes.chooseUniverseEntry("books", "fake", "U1", "S1", Inclusion(true))
        universes.addUniverse("books", UniverseRef("fake", "U1"))
        assertEquals(true, universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }.included)
        universes.resetUniverseEntry("books", "fake", "U1", "S1")

        // The universe is a value to pick for any work, linked to none yet; linked, it names itself
        assertTrue(items.listFacetValues("site", "universe", "space", 10).body!!.any { it.key == "fake:U1" })
        corrections.correctFacet("site", "u1", FacetCorrection(facet = "universe", added = true, key = "fake:U1"))
        assertEquals("Space opera", items.getItem("site", "u1").body!!.allFacets.single { it.facet == "universe" }.propertyValues.single().name)
        assertEquals(1, universes.listUniverses("books").body!!.single { it.universe == "U1" }.works)

        // Removed, it takes its links with it
        universes.removeUniverse("books", "fake", "U1")
        assertTrue(universes.listUniverses("books").body!!.none { it.universe == "U1" })
        assertTrue(items.getItem("site", "u1").body!!.allFacets.none { it.facet == "universe" })
    }

    @Test
    fun `the characters and the pairings of a work are worked out within its universes`() {
        universes.addUniverse("books", UniverseRef("fake", "U2"))
        // Works of the universe, the site's line naming its pairing, and works of no universe
        repeat(12) { k ->
            capture("p$k|Pilots $k|space|Pilots fly starships in orbit near planets $k|Pilot/Captain")
            capture("q$k|Knights $k|dragons|A knight guards the princess in the castle $k|Knight/Princess")
        }
        repeat(12) { k -> corrections.correctFacet("site", "p$k", FacetCorrection(facet = "universe", added = true, key = "fake:U2")) }
        // The user's word on half of them
        repeat(6) { k ->
            listOf("fake:C1", "fake:C2").forEach { corrections.correctFacet("site", "p$k", FacetCorrection(facet = "characters", added = true, key = it)) }
            corrections.correctFacet("site", "p$k", FacetCorrection(facet = "pairings", added = true, key = "pair:fake:C1|fake:C2"))
        }
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store)
        // The pairings are made of the characters the model gave: once more, over what it gave
        suggestions.refresh(store)

        val facets = items.getItem("site", "p9").body!!.allFacets
        val characters = facets.single { it.facet == "characters" }.propertyValues
        assertEquals(setOf("fake:C1", "fake:C2"), characters.filter { it.inferred == true }.map { it.key }.toSet(), "characters $characters")
        assertEquals("Pilot", characters.single { it.key == "fake:C1" }.name)
        val pairing = facets.single { it.facet == "pairings" }.propertyValues.single()
        assertEquals("pair:fake:C1|fake:C2", pairing.key)
        assertEquals("Pilot / Captain", pairing.name)
        // A work of no universe has no characters of it: only its site's line
        val outside = items.getItem("site", "q9").body!!.allFacets.filter { it.facet == "characters" || it.facet == "pairings" }
        assertTrue(outside.all { it.propertyValues.isEmpty() && it.original == "Knight/Princess" }, "outside $outside")
        // Its site's line is shown above
        assertEquals("Pilot/Captain", facets.single { it.facet == "characters" }.original)

        // A new work of the universe: every character it may have is offered, with how often its texts name them
        capture("n1|The captain|space|The captain commands. The captain waits. The captain sleeps|")
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "universe", added = true, key = "fake:U2"))
        textVectors.refresh(store)
        val given = items.getItem("site", "n1").body!!.allFacets.find { it.facet == "characters" }?.propertyValues.orEmpty().map { it.key }
        val offered = suggestionsApi.getCandidates("site", "n1", "characters").body!!
        assertEquals(setOf("fake:C1", "fake:C2", "oc:female", "oc:male"), (offered.map { it.key } + given).toSet(), "offered $offered, given $given")
        assertTrue(offered.none { it.key in given }, "offered $offered, given $given")
        assertTrue(offered.zipWithNext().all { (a, b) -> a.chance!! >= b.chance!! }, "the likeliest first: $offered")
        offered.find { it.key == "fake:C2" }?.let { assertEquals(3, it.mentions) }
        assertEquals(0, offered.single { it.key == "oc:male" }.mentions)
        assertEquals("Captain", offered.find { it.key == "fake:C2" }?.name ?: "Captain")
        assertEquals(404, suggestionsApi.getCandidates("site", "n1", "nope").statusCode.value())

        // A fan fiction of no universe is offered the likeliest universes, likely or not
        capture("f1|Fleet tales|space|Ships of the fleet|")
        corrections.correctFacet("site", "f1", FacetCorrection(facet = "kind", added = true, key = "fanfiction"))
        val universe = suggestionsApi.getSuggestions("site", "f1").body!!.single { it.facet == "universe" }
        assertEquals(listOf("fake:U2"), universe.suggested.map { it.key })
    }
}
