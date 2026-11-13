package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.TagByTextAnswer
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * The tags a work's own text is closest to: each with the paragraph that says it, the user's answers on them — yes, not
 * enough, wrong — and the tags above and under the ones answered.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/site-tags-by-text"])
class SiteTagsByTextTest : SiteTestBase() {

    @Test
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
        val enough =
            tagsByText.answerTagByText(
                "site",
                "tt3",
                TagByTextAnswer(dragons.key, "c1", 1, TagByTextAnswer.Verdict.ENOUGH_NOT),
            )
        // Of no yes, nothing made of the facet
        assertEquals(200 to emptyList(), enough.statusCode.value() to enough.body)
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
        val yes =
            tagsByText.answerTagByText(
                "site",
                "tt3",
                TagByTextAnswer(ships.key, ships.part, ships.paragraph, TagByTextAnswer.Verdict.YES),
            )
        // What the yes made of the facet of tags, for the page to show it at once
        assertTrue(yes.body!!.single().answers.any { it.key == ships.key && it.added }, "answered ${yes.body}")
        assertTrue(store.corrections.facetsOf("tt3").any { it.facet == "tags" && it.key == ships.key && it.added })
        assertTrue(list().tags.none { it.key == ships.key })
        // Of the tags' hierarchy: a tag above one the work has, it has — not asked
        val space = list().tags.single { it.name == "Space" }
        tagsApi.relateTags(TagRelationRequest(ships.key, space.key, TagRelation.PARENT))
        assertTrue(list().tags.none { it.key == space.key }, "list ${list()}")
        // The answer tells the page the tag above it is the work's by it
        val above =
            corrections.correctFacet("site", "tt3", FacetCorrection(facet = "tags", added = true, key = ships.key))
        assertEquals(listOf(space.key), above.body!!.facets.single().implied.map { it.key })
        tagsApi.relateTags(TagRelationRequest(ships.key, space.key, TagRelation.NONE))
        // And a tag under one the work has not, it has not either
        assertTrue(list().tags.any { it.key == dragons }, "list ${list()}")
        tagsApi.relateTags(TagRelationRequest(dragons, space.key, TagRelation.PARENT))
        val denied =
            corrections.correctFacet("site", "tt3", FacetCorrection(facet = "tags", added = false, key = space.key))
        assertTrue(list().tags.none { it.key == dragons }, "list ${list()}")
        // The answer tells the page the tag under it is shown no more
        assertTrue(dragons in denied.body!!.facets.single().hidden, "hidden ${denied.body}")
        corrections.uncorrectFacet("site", "tt3", "tags", space.key)
        tagsApi.relateTags(TagRelationRequest(dragons, space.key, TagRelation.NONE))
        // Wrong: of answers of both kinds the model is fitted, and every tag offered has its chance
        assertEquals(
            200,
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
}
