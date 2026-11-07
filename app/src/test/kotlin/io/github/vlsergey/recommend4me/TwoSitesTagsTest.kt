package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.workload.inBackground
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The tags of two sites: one named by the text itself, counted once of a work of several items, merged across the
 * sites, put above and under one another; a universe or a pairing the dictionary has not, refused.
 */
class TwoSitesTagsTest : TwoSitesTestBase() {

    @Test
    fun `a tag named by the text itself on another site comes before the tags of this site that only hold it`() {
        alpha("r1|Reefs|reefs of the north|Reefs to the north||")
        alpha("r2|Reefs again|reefs of the south|Reefs to the south||")
        beta("r3|Reefs|reefs|Reefs")
        // The tags named and counted after the quiet — here at once
        inBackground { tagService.scope()?.let { tagService.rename(it, it.facet) } }
        val found = items.listFacetValues("alpha", "tags", "reefs", 1).body!!
        assertEquals(listOf(tagOf("beta", "reefs")), found.map { it.key })
    }

    @Test
    fun `a work of several items counts once in the statistics of its tag and of each site's tag`() {
        alpha("h1|The Harbour|harbours|Ships in the harbour||")
        alpha("h2|The Harbour, a new upload|harbours|Ships in the harbour||")
        beta("h3|The Harbour|harbours|Ships in the harbour")
        alpha("h4|Another harbour|harbours|Boats in another harbour||")
        corrections.linkItem("alpha", "h1", ItemRef("alpha", "h2"))
        corrections.linkItem("alpha", "h1", ItemRef("beta", "h3"))
        items.rateItem("beta", "h3", RatingRequest(5))
        inBackground { tagService.scope()?.let { tagService.rename(it, it.facet) } }
        val tag = tags.getTag(tagOf("alpha", "harbours")).body!!
        assertEquals(Triple(2, 1, 1), Triple(tag.works, tag.graded, tag.liked))
        val bySite = tag.variants.associateBy { it.source }
        assertEquals(Triple(2, 1, 1), bySite.getValue("alpha").let { Triple(it.works, it.graded, it.liked) })
        assertEquals(Triple(1, 1, 1), bySite.getValue("beta").let { Triple(it.works, it.graded, it.liked) })
        items.unrateItem("beta", "h3", null)
    }

    @Test
    fun `tags of two sites merged — the user's answers on both move to the one kept, in each site's database`() {
        alpha("a2|Waves|waves|Waves on the shore||")
        alpha("a3|Calm|calm|A calm sea||")
        beta("b2|Breakers|breakers|Breakers on the rocks")
        beta("b3|Still water|calm|Still water")
        val waves = tagOf("alpha", "waves")
        val breakers = tagOf("beta", "breakers")
        // The user's word on works of both sites: the one tag on one, the other on the other
        corrections.correctFacet("alpha", "a3", FacetCorrection(facet = "tags", added = true, key = breakers))
        corrections.correctFacet("beta", "b3", FacetCorrection(facet = "tags", added = false, key = waves))

        tags.relateTags(TagRelationRequest(waves, breakers, TagRelation.MERGED))
        // The names and the counts worked out in the queue after the quiet — here at once
        inBackground { tagService.scope()?.let { tagService.rename(it, it.facet) } }
        val merged = tags.getTag(waves).body!!
        assertEquals(
            setOf(SiteTagRef("alpha", "waves"), SiteTagRef("beta", "breakers")),
            merged.variants.map { SiteTagRef(it.source, it.tag) }.toSet(),
        )
        assertEquals(404, tags.getTag(breakers).statusCode.value())
        fun answers(source: String, item: String) =
            stores.source(source)!!.corrections.facetsOf(item).filter { it.facet == "tags" }.map { it.key to it.added }
        assertEquals(listOf(waves to true), answers("alpha", "a3"))
        assertEquals(listOf(waves to false), answers("beta", "b3"))
    }

    @Test
    fun `a universe or a pairing named by a name typed that the dictionary has not is refused, never made a value`() {
        alpha("n1|Wizards|magic|A school of magic||")
        listOf("universe", "pairings").forEach { facet ->
            val refused =
                assertThrows<Refused> {
                    corrections.correctFacet(
                        "alpha",
                        "n1",
                        FacetCorrection(facet = facet, added = true, name = "Harry Potter"),
                    )
                }
            assertEquals(Refusal.NOT_IN_DICTIONARY, refused.refusal)
            assertEquals(mapOf("value" to "Harry Potter"), refused.params)
        }
        assertTrue(
            stores.source("alpha")!!.corrections.facetsOf("n1").none {
                it.facet == "universe" || it.facet == "pairings"
            }
        )
        // A character may be named as the work names it
        corrections.correctFacet("alpha", "n1", FacetCorrection(facet = "characters", added = true, name = "Neville"))
        assertEquals(
            listOf("neville"),
            stores.source("alpha")!!.corrections.facetsOf("n1").filter { it.facet == "characters" }.map { it.key },
        )
    }

    @Test
    fun `a word on two tags refused leaves what was said of them as it was`() {
        alpha("t1|Fleet|fleet|A fleet sails||")
        alpha("t2|Frigates|frigates|Frigates of the fleet||")
        alpha("t3|Gunboats|gunboats|Gunboats of the frigates||")
        val fleet = tagOf("alpha", "fleet")
        val frigates = tagOf("alpha", "frigates")
        val gunboats = tagOf("alpha", "gunboats")
        tags.relateTags(TagRelationRequest(fleet, frigates, TagRelation.CHILD))
        tags.relateTags(TagRelationRequest(frigates, gunboats, TagRelation.CHILD))
        // And right under the fleet as well
        tags.relateTags(TagRelationRequest(fleet, gunboats, TagRelation.CHILD))
        // The fleet put under the gunboats: it is above them through the frigates — refused, and the
        // link said of the two before is not taken away by the refused word
        assertEquals(
            Refusal.TAG_ABOVE,
            assertThrows<Refused> { tags.relateTags(TagRelationRequest(gunboats, fleet, TagRelation.CHILD)) }.refusal,
        )
        assertTrue(fleet in tags.getTag(gunboats).body!!.parents.map { it.key })
        tags.relateTags(TagRelationRequest(fleet, gunboats, TagRelation.NONE))
        tags.relateTags(TagRelationRequest(frigates, gunboats, TagRelation.NONE))
        tags.relateTags(TagRelationRequest(fleet, frigates, TagRelation.NONE))
    }

    @Test
    fun `the answers of a merge a stop left unmoved are moved on the start`() {
        alpha("t4|Anchors|anchors|Anchors aweigh||")
        beta("t5|Moorings|moorings|At the moorings")
        val anchors = tagOf("alpha", "anchors")
        val moorings = tagOf("beta", "moorings")
        corrections.correctFacet("beta", "t5", FacetCorrection(facet = "tags", added = true, key = moorings))
        // Merged, and stopped before the answers were moved
        val type = stores.type("books")!!
        type.tags.merge(TagRepository.idOf(moorings)!!, TagRepository.idOf(anchors)!!, Instant.now())
        assertEquals(
            listOf(moorings to true),
            stores.source("beta")!!.corrections.facetsOf("t5").filter { it.facet == "tags" }.map { it.key to it.added },
        )

        tagService.start()
        assertEquals(
            listOf(anchors to true),
            stores.source("beta")!!.corrections.facetsOf("t5").filter { it.facet == "tags" }.map { it.key to it.added },
        )
        assertEquals(emptyList(), type.tags.pendingMerges())
    }

    @Test
    fun `a tag under another through a third is marked so among the alike, and is said of the tags between`() {
        alpha("a4|Sailing|sailing|Sails and winds||")
        alpha("a5|Yachts|yachts|A yacht race||")
        alpha("a6|Regatta|regatta|The regatta of yachts||")
        val sailing = tagOf("alpha", "sailing")
        val yachts = tagOf("alpha", "yachts")
        val regatta = tagOf("alpha", "regatta")
        tags.relateTags(TagRelationRequest(sailing, yachts, TagRelation.CHILD))
        tags.relateTags(TagRelationRequest(yachts, regatta, TagRelation.CHILD))

        fun relationOf(tag: String, other: String) =
            tags
                .listSimilarTags(tag, null, 0, 500, null, null)
                .body!!
                .tags
                .single { it.tag.key == other && it.site == null }
                .relation
        assertEquals(TagRelation.CHILD, relationOf(sailing, yachts))
        assertEquals(TagRelation.DESCENDANT, relationOf(sailing, regatta))
        assertEquals(TagRelation.ANCESTOR, relationOf(regatta, sailing))
        assertEquals(TagRelation.PARENT, relationOf(regatta, yachts))
        // Hidden by their filter, the others not
        val shown =
            tags
                .listSimilarTags(sailing, null, 0, 500, null, listOf(TagRelation.DESCENDANT, TagRelation.ANCESTOR))
                .body!!
                .tags
        assertTrue(shown.none { it.tag.key == regatta && it.site == null } && shown.any { it.tag.key == yachts })
        // Never said of the two themselves
        val through =
            assertThrows<Refused> { tags.relateTags(TagRelationRequest(sailing, regatta, TagRelation.DESCENDANT)) }
        assertEquals(Refusal.TAG_THROUGH, through.refusal)
        tags.relateTags(TagRelationRequest(yachts, regatta, TagRelation.NONE))
        tags.relateTags(TagRelationRequest(sailing, yachts, TagRelation.NONE))
    }
}
