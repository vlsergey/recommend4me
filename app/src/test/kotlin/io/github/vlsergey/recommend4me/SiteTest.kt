package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.CatalogueEntryRef
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.PageItem
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TagByTextAnswer
import io.github.vlsergey.recommend4me.api.model.TagEntryRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.model.MeasuringState
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

/**
 * The site as the application's interface: the extension's view of a work's page and of a list, the suggested tags
 * confirmed and rejected, a value added by its name, the works found for a link, the tags by a work's own text.
 */
@Order(3)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteTest : SiteTestBase() {

    @Test
    @Order(1)
    fun `the tags a work's own text is closest to, each with its paragraph, and the user's three answers on them`() {
        val store = stores.source("site")!!
        // Works the site gives tags: the tags' names are what the text is held against
        capture("tt1|Dragons|dragons,space||")
        capture("tt2|Ships|ships||")
        capture("tt3|A tale|||")
        refresh(store)
        // Paragraphs long enough to stand alone, and two short lines of a dialogue, joined into one
        val road =
            "The road was long, and the travellers walked it for days through the rain and the wind, " +
                "past villages and farms and forests, their boots wet and their packs heavy, " +
                "saying little to one another as the miles went by."
        val dragonsText =
            "Dragons flew over the castle, dragons everywhere: the great dragons of the north circled the towers, " +
                "breathing fire on the walls, and the little dragons of the hills followed them, screeching, " +
                "while the guards hid."
        val shipsText =
            "Ships sailed among the stars, ships of space with silver hulls, carrying their crews from one star " +
                "to the next, and the ships of the fleet kept watch at the edge of the system, " +
                "waiting for the ships of the enemy."
        store.parts.save(
            "tt3",
            listOf(PartData("c1", 0, "One", "$road\n***\n$dragonsText\n$shipsText\n— Ships?\n— Ships.", null)),
        )
        assertEquals(
            listOf(road, dragonsText, "$shipsText\n— Ships?\n— Ships."),
            Paragraphs.of("$road\n***\n$dragonsText\n$shipsText\n— Ships?\n— Ships."),
        )
        // Its paragraphs encoded, as the background encodes them: the line of stars is none
        encodeParagraphs(store)
        val dragons = closestTags(dragonsText)
        paragraphMoved(store, road, dragonsText, shipsText, dragons)
        answeredOn(store, dragons)
    }

    /** The paragraphs of the work's parts encoded, as the background encodes them. */
    private fun encodeParagraphs(store: SourceStore) {
        val encoder = SitePlugins.WordsEncoder()
        store.parts.paragraphs.paragraphsToEncode(Paragraphs.keyOf(encoder.id), 10).forEach { part ->
            val texts = Paragraphs.of(part.content)
            store.parts.paragraphs.saveParagraphVectors(
                part.itemId,
                part.partId,
                Paragraphs.keyOf(encoder.id),
                part.hash,
                encoder.encode(texts),
                texts.map(Vectors::hash),
            )
        }
    }

    /** The tags by the text of the work tt3. */
    private fun list() = tagsByText.getTagsByText("site", "tt3").body!!

    /**
     * The tags the text is closest to, each with its paragraph; "not enough" offers the paragraph for the tag no more.
     * The key of the tag of dragons.
     */
    private fun closestTags(dragonsText: String): String {
        val first = list()
        assertTrue(!first.pending && !first.fitted && first.tags.isNotEmpty(), "list $first")
        val dragons = first.tags.single { it.name == "Dragons" }
        assertEquals(dragonsText, dragons.quote)
        assertEquals("c1" to 1, dragons.part to dragons.paragraph)
        assertEquals("One", dragons.partTitle)
        // Not enough: the paragraph is offered for the tag no more, nothing learnt
        assertEquals(
            204,
            tagsByText
                .answerTagByText(
                    "site",
                    "tt3",
                    TagByTextAnswer(dragons.key, "c1", 1, TagByTextAnswer.Verdict.ENOUGH_NOT),
                )
                .statusCode
                .value(),
        )
        assertTrue(list().tags.none { it.key == dragons.key && it.paragraph == 1 }, "list ${list()}")
        return dragons.key
    }

    /**
     * The answer is on the paragraph itself, by the hash of its text: a paragraph put before it, the same one stays
     * hidden at its new number.
     */
    private fun paragraphMoved(
        store: SourceStore,
        road: String,
        dragonsText: String,
        shipsText: String,
        dragons: String,
    ) {
        // The answer is on the paragraph itself, by the hash of its text: a paragraph put before it, the same one stays
        // hidden at its new number
        store.parts.save(
            "tt3",
            listOf(
                PartData(
                    "c1",
                    0,
                    "One",
                    listOf(road, "$road and again", "***", dragonsText, shipsText, "— Ships?", "— Ships.")
                        .joinToString("\n"),
                    null,
                )
            ),
        )
        encodeParagraphs(store)
        assertTrue(list().tags.none { it.key == dragons && it.quote == dragonsText }, "list ${list()}")
    }

    /**
     * The user's answers: yes, the tag the work's; a tag above one the work has or under one it has not never asked;
     * wrong, the model fitted on both kinds of answers; a paragraph there is not, nothing kept.
     */
    private fun answeredOn(store: SourceStore, dragons: String) {
        // Yes: the tag is the work's, answered on it, offered no more
        val ships = list().tags.single { it.name == "Ships" }
        assertEquals(
            204,
            tagsByText
                .answerTagByText(
                    "site",
                    "tt3",
                    TagByTextAnswer(ships.key, ships.part, ships.paragraph, TagByTextAnswer.Verdict.YES),
                )
                .statusCode
                .value(),
        )
        assertTrue(store.corrections.facetsOf("tt3").any { it.facet == "tags" && it.key == ships.key && it.added })
        assertTrue(list().tags.none { it.key == ships.key })
        // Of the tags' hierarchy: a tag above one the work has, it has — not asked
        val space = list().tags.single { it.name == "Space" }
        tagsApi.relateTags(TagRelationRequest(ships.key, space.key, TagRelation.PARENT))
        assertTrue(list().tags.none { it.key == space.key }, "list ${list()}")
        tagsApi.relateTags(TagRelationRequest(ships.key, space.key, TagRelation.NONE))
        // And a tag under one the work has not, it has not either
        assertTrue(list().tags.any { it.key == dragons }, "list ${list()}")
        tagsApi.relateTags(TagRelationRequest(dragons, space.key, TagRelation.PARENT))
        corrections.correctFacet("site", "tt3", FacetCorrection(facet = "tags", added = false, key = space.key))
        assertTrue(list().tags.none { it.key == dragons }, "list ${list()}")
        corrections.uncorrectFacet("site", "tt3", "tags", space.key)
        tagsApi.relateTags(TagRelationRequest(dragons, space.key, TagRelation.NONE))
        // Wrong: of answers of both kinds the model is fitted, and every tag offered has its chance
        assertEquals(
            204,
            tagsByText
                .answerTagByText(
                    "site",
                    "tt3",
                    TagByTextAnswer(space.key, space.part, space.paragraph, TagByTextAnswer.Verdict.WRONG),
                )
                .statusCode
                .value(),
        )
        inBackground { textTags.fit() }
        val fitted = list()
        assertTrue(fitted.fitted && fitted.tags.all { it.chance != null }, "list $fitted")
        // No such paragraph: nothing kept
        assertEquals(
            404,
            tagsByText
                .answerTagByText("site", "tt3", TagByTextAnswer(dragons, "c1", 9, TagByTextAnswer.Verdict.YES))
                .statusCode
                .value(),
        )
    }

    @Test
    @Order(2)
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
        // alone: the crossovers have the other kind's too, and the fittings the tests before set off in
        // the background may give it a chance of its own
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
        val series = items.getItem("site", "s1").body!!.related.single()
        assertEquals(
            Triple("Галактика", ApiFacetRole.SERIES, listOf("s2")),
            Triple(series.name, series.role, series.items.map { it.item }),
        )
        assertEquals(5, series.items.single().grade)

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
}
