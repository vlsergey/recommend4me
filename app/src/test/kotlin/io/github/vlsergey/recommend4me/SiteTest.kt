package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.CatalogueEntryRef
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.PageItem
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TagEntryRequest
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.model.MeasuringState
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource

/**
 * The site as the application's interface: the extension's view of a work's page and of a list, the suggested tags
 * confirmed and rejected, a value added by its name, the works found for a link, a work's chances still to come. Every
 * test of it over the same [catalogue], each making it.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/site"])
class SiteTest : SiteTestBase() {

    @Test
    fun `a work's page and a list show what the application knows, and the work's tags are corrected on them`() {
        catalogue()

        val page = pages.getPage("https://site.example/work/x1?from=list").body!!
        assertEquals("site", page.source)
        assertEquals("x1", page.itemId)
        assertEquals(".tags", page.decor.facets.single().after)
        val item = assertNotNull(page.item)
        // A facet the work has no values of comes too, by the key of its label: the page adds values
        // to it. The application's layer is labelled in the standard namespace, the site's own in its own
        assertEquals("standard:facet.universe", item.facets.single { it.facet == "universe" }.labelKey)
        assertEquals("site:facet.pairing", item.facets.single { it.facet == "pairing" }.labelKey)
        assertEquals("standard:facet.tags", item.facets.single { it.facet == "tags" }.labelKey)
        tagsOfItsKind(item)
        pairingOfItsKind(item)
        siteTagsShown()
        tagsCorrected()
        tagsAddedByName()
        predictionExplained()
        scorersCompared()
        answeredBeforeTraining()
        seriesAndCards()
    }

    /** The work's tags worked out from the site's: the untagged work [item] given those of its kind, others offered. */
    private fun tagsOfItsKind(item: PageItem) {
        // The work's tags are worked out from the site's: the untagged work is given those of its kind
        val given = item.facets.single { it.facet == "tags" }.propertyValues
        assertTrue(given.any { it.key == tag("space") && it.inferred == true }, "given $given")
        assertTrue(given.none { it.key == tag("dragons") && it.offered != true }, "given $given")
        // Beside what it is given, the likeliest others are offered: three the site does not give, at least
        assertEquals(3, given.count { it.corrected == null }, "given $given")
        // Asked for more: every other value of the facet, the likeliest first — the offered ones
        // among them, beyond them those whose chances are not kept
        val more = suggestionsApi.getCandidates("site", "x1", "tags", null, null).body!!
        val offeredKeys = given.filter { it.offered == true }.map { it.key }.toSet()
        assertTrue(
            more.map { it.key }.containsAll(offeredKeys) && more.size > offeredKeys.size,
            "more $more, offered $offeredKeys",
        )
        assertTrue(
            more.none { c -> given.any { it.key == c.key && it.offered != true } },
            "none the work is given: $more",
        )
        assertTrue(more.zipWithNext().all { (a, b) -> a.chance!! >= b.chance!! }, "the likeliest first: $more")
    }

    /** The pairing of its kind given to the work [item] at once, marked as the model's, the likeliest of all. */
    private fun pairingOfItsKind(item: PageItem) {
        // The pairing of its kind is given to it at once, marked as the model's, the likeliest of all. Not
        // alone: the crossovers have the other kind's too, and the fittings set off in the background — of
        // another test of the class too — may give it a chance of its own
        val pairings = item.facets.single { it.facet == "pairing" }.propertyValues
        val pairing = assertNotNull(pairings.find { it.key == "pilot/captain" }, "pairings $pairings")
        assertEquals(true, pairing.inferred)
        assertTrue(pairing.chance!! > 0.5, "the chance ${pairing.chance}")
        assertTrue(pairings.all { it === pairing || (it.chance ?: 0.0) < pairing.chance!! }, "the likeliest: $pairings")
    }

    /** The site's tags its word, shown as they are; of the work's, the site's mistaken one the least likely. */
    private fun siteTagsShown() {
        // The site's tags are its word, shown as they are; of the work's, the site's mistaken one is the least likely
        val w1 = items.getItem("site", "w1").body!!.allFacets
        assertEquals(
            setOf("space", "ships", "magic"),
            w1.single { it.facet == "tag" }.propertyValues.map { it.key }.toSet(),
        )
        assertEquals(false, w1.single { it.facet == "tag" }.editable)
        val worked = w1.single { it.facet == "tags" }
        assertEquals(true, worked.editable)
        // A value not given at all is less likely than not
        fun chanceOf(key: String) = worked.propertyValues.find { it.key == key }?.chance ?: 0.0
        assertTrue(
            chanceOf(tag("space")) > 0.5 && chanceOf(tag("ships")) > 0.5,
            "the work's tags ${worked.propertyValues}",
        )
        assertTrue(
            chanceOf(tag("magic")) < chanceOf(tag("space")) && chanceOf(tag("magic")) < chanceOf(tag("ships")),
            "magic on a space work: ${worked.propertyValues}",
        )
        // The site's tag is shown all the same, unlikely as it is: given, or offered to answer on — never hidden
        val magic = worked.propertyValues.find { it.key == tag("magic") }
        assertNotNull(magic, "the site's magic of w1: ${worked.propertyValues}")
        // By its name — any case until the names are worked out again — with its chance
        assertEquals("magic", magic.name.lowercase())
        assertNotNull(magic.chance, "the chance of magic of w1: $magic")
        siteTagsMarked(worked)
    }

    /** The site's tags of the work's tags [worked] marked as the site's, before the model's own offers. */
    private fun siteTagsMarked(worked: ItemFacet) {
        // Marked as the site's, told apart from the model's offers; the model's own come after every one of the site's
        assertTrue(
            listOf("space", "ships", "magic").all { k ->
                worked.propertyValues.single { it.key == tag(k) }.fromSite == true
            },
            "${worked.propertyValues}",
        )
        assertTrue(
            worked.propertyValues.none {
                it.fromSite == true && it.key !in setOf(tag("space"), tag("ships"), tag("magic"))
            },
            "${worked.propertyValues}",
        )
        val siteAt = worked.propertyValues.indexOfLast { it.fromSite == true }
        assertTrue(
            worked.propertyValues.withIndex().none { (i, v) -> v.offered == true && v.fromSite != true && i < siteAt },
            "${worked.propertyValues}",
        )
    }

    /**
     * The site's word not corrected; the work's tags are: a value given and confirmed the user's, one taken away shown
     * so.
     */
    private fun tagsCorrected() {
        // The site's word is not corrected
        val refused =
            assertThrows<Refused> {
                corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, key = "space"))
            }
        assertEquals(Refusal.SITE_FIELD, refused.refusal)
        // The field by the key of its label: the site's tags are a standard facet
        assertEquals(mapOf("field" to "standard:facet.tag"), refused.params)

        // The work's tags are: a value given and confirmed is the user's, one taken away is shown so
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, key = tag("space")))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = false, key = tag("ships")))
        val facets =
            pages
                .getPage("https://site.example/work/x1")
                .body!!
                .item!!
                .facets
                .single { it.facet == "tags" }
                .propertyValues
        assertEquals(FacetValueInfo.Corrected.CONFIRMED, facets.single { it.key == tag("space") }.corrected)
        // A value taken away is still shown, crossed out, with the model's chance to keep its place by
        val ships = facets.single { it.key == tag("ships") }
        assertEquals(FacetValueInfo.Corrected.REMOVED, ships.corrected, "ships taken away: $facets")
        assertTrue(ships.chance != null, "ships keeps its chance: $ships")
    }

    /**
     * A tag added by its name finds the tag of that name; a new one is a new tag; one of a catalogue's entry is made of
     * the entry.
     */
    private fun tagsAddedByName() {
        // A tag added by its name finds the tag of that name, any case; a new one is a new normalized tag of that name
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "MAGIC"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "Космос"))
        val values = items.getItem("site", "x1").body!!.allFacets.single { it.facet == "tags" }.propertyValues
        // Added — or confirmed, where the model gave it too
        val answeredYes = setOf(FacetValueInfo.Corrected.ADDED, FacetValueInfo.Corrected.CONFIRMED)
        val space = values.single { it.name == "Космос" }.key
        assertEquals(
            setOf(tag("space"), tag("magic"), space),
            values.filter { it.corrected in answeredYes }.map { it.key }.toSet(),
        )
        // Found by its name first; the others it lacks to make the number, by the meaning of theirs, each once
        val mag = searchController.listFacetValues("site", "tags", "mag", 10).body!!.map { it.key }
        assertEquals(tag("magic"), mag.first())
        assertEquals(mag.distinct(), mag)
        // No tag goes by the name typed: the entries of the catalogue after the tags, each a new tag;
        // given, the tag is made of the entry, named by its label, bound to it
        val vessel = searchController.listFacetValues("site", "tags", "vessel", 10).body!!.single { it.entry != null }
        assertEquals("fake" to "E2", vessel.entry!!.catalogue to vessel.entry!!.entry)
        val made =
            corrections
                .correctFacet(
                    "site",
                    "x1",
                    FacetCorrection(facet = "tags", added = true, entry = CatalogueEntryRef("fake", "E2")),
                )
                .body!!
        assertEquals("Судно", made.name)
        assertEquals(listOf("E2"), tagsApi.getTag(made.key).body!!.catalogueEntries.map { it.entry })
        // The entry is a tag's now: offered as that tag, not as a new one; given again, the same tag
        assertTrue(searchController.listFacetValues("site", "tags", "vessel", 10).body!!.none { it.entry != null })
        assertEquals(
            made.key,
            corrections
                .correctFacet(
                    "site",
                    "x1",
                    FacetCorrection(facet = "tags", added = true, entry = CatalogueEntryRef("fake", "E2")),
                )
                .body!!
                .key,
        )
        // As it was: the entry is the other tests'
        corrections.uncorrectFacet("site", "x1", "tags", made.key)
        tagCataloguesApi.bindTagEntry(TagEntryRequest(made.key, "fake", "E2", false))
    }

    /**
     * The prediction told part by part, each part with its features, what every part is worth; what a model that had
     * not seen a graded work says, read apart.
     */
    private fun predictionExplained() {
        // The prediction is told part by part, each part with its features; a page of the site gets the parts alone
        (3..8).forEach { k ->
            gradesController.rateItem("site", "s$k", RatingRequest(5))
            gradesController.rateItem("site", "d$k", RatingRequest(1))
        }
        inBackground { recommendations.retrainAndScore("books") }
        // What every part is worth: the ranking cross-validated without it, every part measured
        val worth = inBackground { inputMeasures.worth("books")!! }
        // The parts by the keys of their labels: the site's tags a standard facet, the title the standard text
        val tagLabel = "standard:facet.${Books.TAG.key}"
        assertTrue(
            worth.full != null && worth.parts.any { it.labelKey == tagLabel && it.metrics != null },
            "parts ${worth.parts.map { it.labelKey }}",
        )
        val explained = items.getItem("site", "x1").body!!.explanation
        assertTrue(
            explained.any { it.labelKey == tagLabel && it.features.isNotEmpty() },
            "parts ${explained.map { it.labelKey }}",
        )
        // A feature of a value: the facet's label and the value as the data names it
        val tagFeature = explained.single { it.labelKey == tagLabel }.features.first()
        assertEquals(tagLabel, tagFeature.labelKey)
        assertNotNull(tagFeature.value)
        assertTrue(
            explained.all { g -> ':' in g.labelKey && g.features.all { ':' in it.labelKey } },
            "every label a key: $explained",
        )
        assertTrue(
            explained.zipWithNext().all { (a, b) -> abs(a.contribution) >= abs(b.contribution) },
            "strongest first",
        )
        val onPage = explanationController.getExplanation("site", "x1").body!!
        assertTrue(onPage.isNotEmpty() && onPage.all { it.features.isEmpty() }, "on a page $onPage")
        // What a model that had not seen a graded work says is read apart, fitted in the queue: being
        // fitted, the request answers so and does not wait; an ungraded work has none
        assertEquals(HttpStatus.ACCEPTED, explanationController.getHeldOut("site", "s3").statusCode)
        var heldOut = explanationController.getHeldOut("site", "s3")
        val fitted = System.currentTimeMillis() + 30_000
        while (heldOut.statusCode == HttpStatus.ACCEPTED && System.currentTimeMillis() < fitted) {
            Thread.sleep(50)
            heldOut = explanationController.getHeldOut("site", "s3")
        }
        assertEquals(HttpStatus.OK, heldOut.statusCode)
        assertTrue(heldOut.body!!.explanation.isNotEmpty())
        assertEquals(HttpStatus.NO_CONTENT, explanationController.getHeldOut("site", "x1").statusCode)
    }

    /** The scorers compared in the background, a comparison under way answered as it stands. */
    private fun scorersCompared() {
        // The scorers are compared in the background, each one's result there once it is measured;
        // a comparison under way is answered as it stands, not started again
        val comparison = comparisons.start("books")
        val again = comparisons.start("books")
        if (again !== comparison)
            assertNotNull(comparison.finishedAt, "a second comparison started while the first was under way")
        val until = System.currentTimeMillis() + 60_000
        while (comparison.finishedAt == null && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(comparison.grades > 0, "measured on ${comparison.grades} grades")
        val measured = comparison.scorers.single()
        assertEquals(MeasuringState.DONE, measured.state, measured.error)
        assertTrue(measured.chosen != null && measured.millis != null, "the line measured: ${measured.chosen}")
    }

    /** Graded works answered on before the model is trained again: a work told of as it is now. */
    private fun answeredBeforeTraining() {
        // Graded works answered on before the model is trained again: a tag two graded works now
        // have is a feature the kept model does not know, and a work is still told of as it is now
        corrections.correctFacet("site", "s4", FacetCorrection(facet = "tags", added = true, name = "Совсем новый тег"))
        corrections.correctFacet("site", "s3", FacetCorrection(facet = "tags", added = true, name = "Совсем новый тег"))
        val graded = pages.getPage("https://site.example/work/s3").body!!.item!!
        assertTrue(
            graded.facets.single { it.facet == "tags" }.propertyValues.any { it.name == "Совсем новый тег" },
            "s3 ${graded.facets}",
        )
    }

    /**
     * The other works of the series, the cards of a list with the works' grades, a work found by its title or address,
     * a page of no work, an error told by its key.
     */
    private fun seriesAndCards() {
        // The other works of the series, with the user's grades
        capture("s1|Space 1|space|Starships cross the galaxy 1|Pilot/Captain|Галактика")
        capture("s2|Space 2|space|Starships cross the galaxy 2|Pilot/Captain|Галактика")
        gradesController.rateItem("site", "s2", RatingRequest(5))
        val series = items.getItem("site", "s1").body!!.series!!
        assertEquals("Галактика", series.title)
        assertEquals(listOf("s1", "s2"), series.works.map { it.work.item })
        assertEquals(5, series.works.single { it.work.item == "s2" }.work.grade)
        assertTrue(items.getItem("site", "s1").body!!.related.none { it.role == ApiFacetRole.SERIES })

        // The series' page seen: a work it lists is of it, the works in its order; listed whole, a work not
        // listed is of it no more
        capture("s3|Space 0|space|Starships cross the galaxy 0|Pilot/Captain")
        captures.capturePage(
            CaptureRequest("${SitePlugins.SiteSource.SERIES}галактика", "Галактика|Starships.|s3,s2,s1")
        )
        val seen = items.getItem("site", "s3").body!!.series!!
        assertEquals(listOf("s3", "s2", "s1"), seen.works.map { it.work.item })
        assertEquals(listOf(1, 2, 3), seen.works.map { it.places.single().position })
        assertEquals("Starships.", seen.sites.single().description)
        assertTrue(seen.works.all { it.bySite && !it.said })
        assertTrue(seen.sites.single().seen)
        captures.capturePage(CaptureRequest("${SitePlugins.SiteSource.SERIES}галактика", "Галактика|Starships.|s3,s1"))
        assertEquals(null, items.getItem("site", "s2").body!!.series)
        assertEquals(listOf("s3", "s1"), items.getItem("site", "s1").body!!.series!!.works.map { it.work.item })

        // The cards of a list get the works' grades
        gradesController.rateItem("site", "s1", RatingRequest(4))
        val cards =
            pages
                .getCards(
                    CardsRequest(
                        "https://site.example/list",
                        listOf("/work/s1", "/work/unknown", "https://elsewhere.example/work/s2"),
                    )
                )
                .body!!
        assertEquals(listOf("/work/s1"), cards.map { it.link })
        assertEquals(4, cards.single().grade)

        // A work is found by a piece of its title and by the address of its page
        assertEquals(listOf("x1"), searchController.lookupItems("books", "lost fl", 10).body!!.map { it.item })
        assertEquals(
            "d3",
            searchController.lookupItems("books", "https://site.example/work/d3", 10).body!!.first().item,
        )

        // A page of no work, and a page of no source
        assertNull(pages.getPage("https://site.example/about").body!!.item)
        assertEquals(404, pages.getPage("https://nowhere.example/").statusCode.value())

        // An error says what is wrong: by the key of its words, filled with what was asked
        val error =
            assertThrows<Refused> {
                corrections.correctFacet("site", "x1", FacetCorrection(facet = "nope", added = true, key = "x"))
            }
        assertEquals("standard:error.noField", error.refusal.key)
        assertEquals(mapOf("field" to "nope"), error.params)
    }

    @Test
    fun `a work captured just now, its texts not encoded yet, has no chance of the model till they are, and says so`() {
        // The model of the facets learnt of works that have their texts
        catalogue()
        capture("n1|New one|space|Starships cross the galaxy, pilots fight in orbit near planets|Pilot/Captain")
        val store = stores.source("site")!!
        // The texts are encoded a few seconds after the capture: that done, its vectors taken away again — the work
        // as it is before they are made, nothing to make them again
        val until = System.currentTimeMillis() + 30_000
        fun encoded() = store.textVectors.halvesOf(listOf("n1"), "words")
        while (encoded().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
        encoded().getValue("n1").keys.forEach { store.textVectors.delete("n1", it) }
        // Of no input the model ever learnt of — every work it learnt from had its texts — it can tell nothing: no
        // value given by it, no chance of any, on the page as in the work's details; the site's tag shown all the same
        fun worked(facets: List<ItemFacet>) = facets.filter { it.facet == "tags" || it.facet == "pairing" }
        val unjudgedPage = pages.getPage("https://site.example/work/n1")
        val unjudged = items.getItem("site", "n1")
        // Its chances still to come: the page and the details say so, the interface asking again
        assertEquals(HttpStatus.ACCEPTED, unjudgedPage.statusCode)
        assertEquals(HttpStatus.ACCEPTED, unjudged.statusCode)
        assertTrue(unjudgedPage.body!!.item!!.pending && unjudged.body!!.pending)
        val page = worked(unjudgedPage.body!!.item!!.facets)
        val details = worked(unjudged.body!!.allFacets)
        listOf(page, details).forEach { facets ->
            val values = facets.flatMap { it.propertyValues }
            assertTrue(values.none { it.chance != null || it.inferred == true }, "of the model: $values")
            assertTrue(values.any { it.key == tag("space") }, "the site's tag: $values")
        }
        // Its texts encoded, it is judged by them
        refresh(store)
        val judgedPage = pages.getPage("https://site.example/work/n1")
        val judged = worked(judgedPage.body!!.item!!.facets)
        assertTrue(judged.single { it.facet == "tags" }.propertyValues.all { it.chance != null }, "judged $judged")
        // Nothing more to come
        assertEquals(HttpStatus.OK, judgedPage.statusCode)
        assertFalse(judgedPage.body!!.item!!.pending)
        assertEquals(HttpStatus.OK, items.getItem("site", "n1").statusCode)
    }
}
