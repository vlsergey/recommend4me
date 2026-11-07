package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.SitePlugins.SiteSource
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.HierarchyOfferKind
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TagAboveRequest
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagEntryRequest
import io.github.vlsergey.recommend4me.api.model.TagIgnoredRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.api.model.TooGeneralRequest
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows

/**
 * The site's tags as the application's own: described by the site when asked, bound to normalized tags the user merges,
 * names, binds to a catalogue's entries and puts above and under one another.
 */
@Order(1)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteTagsTest : SiteTestBase() {

    @Test
    @Order(1)
    fun `a site's tag not described yet is asked of the browser once, and what the site answers describes it`() {
        capture("w1|Wreck one|wrecks,hulks|A wreck adrift|")
        val type = stores.type("books")!!
        fun tagOf(site: String) = TagRepository.key(type.tags.bindings().getValue(SiteTagRef("site", site)))
        val wrecks = tagOf("wrecks")
        val hulks = tagOf("hulks")
        refresh(stores.source("site")!!)
        // Asked when the tag is looked at: the request written, not waited for
        assertEquals(listOf("site"), tagsApi.getTag(wrecks).body!!.awaitedDescriptions)
        assertEquals(listOf("site"), tagsApi.getTag(hulks).body!!.awaitedDescriptions)
        // Handed to the browser with the answer of the next page of the site, the oldest first
        val asked = capture("w2|Wreck two|wrecks|Another wreck|").body!!.wanted!!
        assertEquals(listOf(SiteSource.describe("wrecks"), SiteSource.describe("hulks")), asked.map { it.url })
        assertEquals(mapOf("tag" to "wrecks"), asked.first().form)
        // The site's answers sent back: the one it describes described; neither asked again, the one
        // the site said nothing of either
        captures.capturePage(CaptureRequest(SiteSource.describe("wrecks"), "Ships broken apart"))
        captures.capturePage(CaptureRequest(SiteSource.describe("hulks"), ""))
        val described = tagsApi.getTag(wrecks).body!!
        assertEquals(
            listOf("Ships broken apart"),
            described.descriptions.filter { it.origin == "site" }.map { it.text },
        )
        assertEquals(emptyList(), described.awaitedDescriptions)
        assertEquals(emptyList(), tagsApi.getTag(hulks).body!!.awaitedDescriptions)
        assertEquals(emptyList(), capture("w3|Wreck three|wrecks,hulks|A third wreck|").body!!.wanted)
    }

    @Test
    @Order(2)
    fun `a site's tag bound to a normalized tag, the user merges, names, takes a site's tag out and links them`() {
        // A synonym the site names of its tag before any work has it: kept, bound once the tag is
        captures.capturePage(CaptureRequest("${SiteSource.SYNONYMS}cargo", "Freight"))
        assertNull(stores.type("books")!!.tags.bindings()[SiteTagRef("site", "freight")])
        capture("t1|Hull one|hulls,cargo|Ships carry cargo between stations|")
        capture("t2|Hull two|starhulls|Freighters carry cargo between stations|")
        capture("t3|Hull three|hulls|A hull breach in deep space|")
        // A work with both site's tags that will be one tag
        capture("t4|Hull four|hulls,starhulls|Two hulls docked in deep space|")
        // Every site's tag bound as it is captured, each to a normalized tag of its own name
        val hulls = tag("hulls")
        val starhulls = tag("starhulls")
        val cargo = tag("cargo")
        assertEquals(3, setOf(hulls, starhulls, cargo).size)
        // The synonym a site's tag of its own, bound to the same tag; one named of a tag bound already, at once
        assertEquals(cargo, tag("freight"))
        captures.capturePage(CaptureRequest("${SiteSource.SYNONYMS}cargo", "Freight, Shipment"))
        assertEquals(cargo, tag("shipment"))
        // The user's answer on a tag that will be merged into another
        corrections.correctFacet("site", "t3", FacetCorrection(facet = "tags", added = true, key = starhulls))
        gradesController.rateItem("site", "t2", RatingRequest(5))
        refresh(stores.source("site")!!)
        // The synonyms variants of the tag, of no work, with their names: the tag found by them
        val ofCargo = tagsApi.getTag(cargo).body!!.variants.associate { it.tag to (it.name to it.works) }
        assertEquals(
            mapOf("cargo" to ("Cargo" to 1), "freight" to ("Freight" to 0), "shipment" to ("Shipment" to 0)),
            ofCargo,
        )
        assertTrue(tagsApi.listTags("shipment", null, null, null, 0, 10).body!!.tags.any { it.key == cargo })
        merged(hulls, starhulls)
        boundToAnEntry(hulls)
        ignored(hulls)
        secondEntry(hulls)
        offeredByTheCatalogue(hulls, cargo)
        val craft = newTagAbove(hulls, cargo)
        tooGeneral(hulls, cargo, craft)
        val other = takenOut(hulls, starhulls)
        toldApart(hulls, other)
        underATag(hulls, cargo, other)
        madeUp(hulls)
    }

    /**
     * Offered alike, nothing merged by itself; merged, the site's tags of the other bound to [hulls], named by the
     * liked work, then by the user, the answer on the other the tag's now.
     */
    private fun merged(hulls: String, starhulls: String) {
        // Offered alike: the other tags of the sites, with how alike their names are; the site's own tag of it, merged
        val similar = tagsApi.listSimilarTags(hulls, null, 0, 50, null, null).body!!.tags
        assertTrue(similar.any { it.tag.key == starhulls && it.site == null }, "similar ${similar.map { it.tag.key }}")
        assertEquals(listOf("hulls"), similar.filter { it.site != null }.map { it.site!!.tag })
        // Nothing is merged by itself
        assertEquals(listOf("hulls"), tagsApi.getTag(hulls).body!!.variants.map { it.tag })

        // Merged: the site's tags of the other bound to this one, the other no more. The word answered
        // at once, the names and the counts worked out in the queue after the quiet — here at once
        assertEquals(hulls, tagsApi.relateTags(TagRelationRequest(hulls, starhulls, TagRelation.MERGED)).body!!.key)
        inBackground { tagService.scope()?.let { tagRenaming.rename(it, it.facet) } }
        val merged = tagsApi.getTag(hulls).body!!
        assertEquals(hulls, merged.key)
        assertEquals(setOf("hulls", "starhulls"), merged.variants.map { it.tag }.toSet())
        assertEquals(4, merged.works)
        // Each site's tag with its own works: the work with both counted in both, once in the tag
        assertEquals(mapOf("hulls" to 3, "starhulls" to 2), merged.variants.associate { it.tag to it.works })
        assertEquals(hulls, tag("starhulls"))
        assertEquals(404, tagsApi.getTag(starhulls).statusCode.value())
        // Named by the name of the liked work: t2, graded 5, has "Starhulls"
        assertEquals("Starhulls", merged.name)
        assertEquals(TagNameRule.LIKED, merged.rule)
        // Its names in the order the name is chosen by: the liked work's first, the tag's name
        assertEquals(listOf("Starhulls", "Hulls"), merged.names)
        // The answer on the tag merged is the tag's now
        val t3 = stores.source("site")!!.corrections.facetsOf("t3").filter { it.facet == "tags" }
        assertEquals(listOf(hulls to true), t3.map { it.key to it.added })
        // The work's tags show the tag by its name, whichever site's name it has
        val named = tagsApi.nameTag(TagNameRequest(hulls, "Корпуса")).body!!
        assertEquals("Корпуса", named.name)
        assertEquals(TagNameRule.EXPLICIT, named.rule)
        // Named by the user: the sites' names all follow it, in the same order
        assertEquals(listOf("Starhulls", "Hulls"), named.names)
        val shown =
            items
                .getItem("site", "t3")
                .body!!
                .allFacets
                .single { it.facet == "tags" }
                .propertyValues
                .single { it.key == hulls }
        assertEquals("Корпуса", shown.name)
        // Found by the name of a site's tag of it, not its own; once, by its own name; the rest by meaning
        val found = searchController.listFacetValues("site", "tags", "starhull", 10).body!!
        assertEquals(hulls to "Корпуса", found.first().let { it.key to it.name })
        assertEquals(1, found.count { it.key == hulls })
        // The name taken back: the sites' names decide again
        assertEquals(TagNameRule.LIKED, tagsApi.nameTag(TagNameRequest(hulls)).body!!.rule)
    }

    /**
     * [hulls] bound to an entry of a catalogue the user found: named by its label, found by any of its names, filtered
     * by what it comes from.
     */
    private fun boundToAnEntry(hulls: String) {
        // Bound to an entry of a catalogue, found by the user: named by its label in the user's first language
        val entries = tagCataloguesApi.findTagEntries("hull").body!!
        assertEquals(listOf("fake" to "E1"), entries.map { it.catalogue to it.entry })
        assertEquals(emptyList(), entries.single().tags)
        val bound = tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E1", true)).body!!
        assertEquals("Корпус" to TagNameRule.CATALOGUE, bound.name to bound.rule)
        assertEquals(setOf("Hull", "Корпус", "Ship hull"), bound.catalogueEntries.single().names.toSet())
        assertEquals(
            listOf(hulls to "Корпус"),
            tagCataloguesApi.findTagEntries("E1").body!!.single().tags.map { it.key to it.name },
        )
        // Found by any name of its entry, in any language
        assertTrue(tagsApi.listTags("ship hull", null, null, null, 0, 10).body!!.tags.any { it.key == hulls })
        // Filtered by what it comes from: the site and the catalogue it is bound to an entry of
        val ofCatalogue = tagsApi.listTags(null, listOf("fake"), null, null, 0, 1000).body!!
        assertEquals(listOf("site", "fake"), ofCatalogue.sources)
        assertEquals(listOf(hulls), ofCatalogue.tags.map { it.key })
        assertTrue(
            tagsApi.listTags(null, listOf("site"), listOf("fake"), null, 0, 1000).body!!.tags.none { it.key == hulls }
        )
        assertTrue(tagsApi.listTags(null, listOf("site"), null, null, 0, 1000).body!!.tags.any { it.key == hulls })
    }

    /** [hulls] ignored: kept with the work and found by the filter, never read by the model, never offered to mark. */
    private fun ignored(hulls: String) {
        // Ignored: kept with the work and found by the filter, never read by the model of the grades
        val t3Key = ItemKey("site", "t3")
        val feature = "facet:tags:$hulls"
        assertTrue(feature in catalogueReader.item(stores.type("books")!!, t3Key)!!.categorical)
        assertTrue(tagsApi.ignoreTag(TagIgnoredRequest(hulls, true)).body!!.ignored)
        assertEquals(listOf(hulls), tagsApi.listTags(null, null, null, true, 0, 1000).body!!.tags.map { it.key })
        assertTrue(tagsApi.listTags(null, null, null, false, 0, 1000).body!!.tags.none { it.key == hulls })
        assertTrue(feature !in catalogueReader.item(stores.type("books")!!, t3Key)!!.categorical)
        assertTrue(
            items
                .getItem("site", "t3")
                .body!!
                .allFacets
                .single { it.facet == "tags" }
                .propertyValues
                .any { it.key == hulls }
        )
        // Never offered to mark: not among the candidates, not found to add
        assertTrue(suggestionsApi.getCandidates("site", "t2", "tags", null, null).body!!.none { it.key == hulls })
        assertTrue(searchController.listFacetValues("site", "tags", "hull", 10).body!!.none { it.key == hulls })
        assertTrue(searchController.listFacetValues("site", "tags", null, 500).body!!.none { it.key == hulls })
        assertTrue(!tagsApi.ignoreTag(TagIgnoredRequest(hulls, false)).body!!.ignored)
        assertTrue(searchController.listFacetValues("site", "tags", "hull", 10).body!!.any { it.key == hulls })
        assertTrue(feature in catalogueReader.item(stores.type("books")!!, t3Key)!!.categorical)
    }

    /**
     * A second entry of [hulls] besides: the one bound first still names it, the descriptions of every one; unbound,
     * the sites' names again.
     */
    private fun secondEntry(hulls: String) {
        // A second entry besides: the one bound first still names it; the descriptions of the site's tag's page first,
        // then of each entry
        stores.source("site")!!.items.describeFacetValues(Books.TAG.key, mapOf("hulls" to "The hulls of ships"))
        val both = tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E2", true)).body!!
        assertEquals(listOf("E1", "E2"), both.catalogueEntries.map { it.entry })
        assertEquals("Корпус", both.name)
        assertEquals(
            listOf("site" to "The hulls of ships", "fake" to "the body of a ship", "fake" to "a ship"),
            tagsApi.getTag(hulls).body!!.descriptions.map { it.origin to it.text },
        )
        // The user's own name over the entry's
        assertEquals(TagNameRule.EXPLICIT, tagsApi.nameTag(TagNameRequest(hulls, "Корпуса")).body!!.rule)
        tagsApi.nameTag(TagNameRequest(hulls))
        // The first no more: the other names it; bound to none, the sites' names again
        val second = tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E1", false)).body!!
        assertEquals(listOf("E2"), second.catalogueEntries.map { it.entry })
        assertEquals("Судно", second.name)
        val unbound = tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E2", false)).body!!
        assertEquals(emptyList(), unbound.catalogueEntries)
        assertEquals(TagNameRule.LIKED, unbound.rule)
    }

    /** A hull a kind of vessel in the catalogue: the tag of the vessel, [cargo], offered above [hulls] till said. */
    private fun offeredByTheCatalogue(hulls: String, cargo: String) {
        // A hull is a kind of vessel in the catalogue: the tag of the vessel offered above the hulls, the hulls under
        // it
        tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E1", true))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E2", true))
        val offered = tagsApi.getTag(hulls).body!!.catalogueOffers.single()
        assertEquals(
            Triple(cargo, TagRelation.PARENT, listOf("Корпус", "Судно")),
            Triple(offered.tag.key, offered.relation, offered.path),
        )
        assertEquals(
            listOf(hulls to TagRelation.CHILD),
            tagsApi.getTag(cargo).body!!.catalogueOffers.map { it.tag.key to it.relation },
        )
        // Read again from the card: the same entries, the same offer
        assertEquals(listOf("E1"), tagCataloguesApi.refreshTagEntries(hulls).body!!.catalogueEntries.map { it.entry })
        // The page's offers: the hulls under the cargo; no new tag above both, where the cargo's own entry is
        val page = offers()
        val link = page.single()
        assertEquals(HierarchyOfferKind.LINK to listOf(hulls, cargo), link.kind to link.tags.map { it.key })
        assertEquals(listOf("Корпус", "Судно"), link.path)
        // Said: offered no more
        tagsApi.relateTags(TagRelationRequest(hulls, cargo, TagRelation.PARENT))
        assertEquals(emptyList(), tagsApi.getTag(hulls).body!!.catalogueOffers)
        tagsApi.relateTags(TagRelationRequest(hulls, cargo, TagRelation.NONE))
    }

    /**
     * A hull and a sail kinds of craft: a new tag of it offered above both and made, or a tag of its name bound to it;
     * a link over its head offered to be taken away. The key of the tag of the craft.
     */
    private fun newTagAbove(hulls: String, cargo: String): String {
        // A hull and a sail are kinds of craft, the nearest they both are: a new tag of it offered above both, made
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E2", false))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E4", true))
        val above = offers().single()
        assertEquals(
            Triple(HierarchyOfferKind.NEW_PARENT, "E3", setOf(hulls, cargo)),
            Triple(above.kind, above.entry, above.tags.map { it.key }.toSet()),
        )
        // Said too general: still listed, as rejected — the screen hides it unless asked — and taken back
        tagCataloguesApi.judgeTagEntryTooGeneral(TooGeneralRequest("fake", "E3", true))
        assertEquals(listOf(true), offers().map { it.rejected })
        tagCataloguesApi.judgeTagEntryTooGeneral(TooGeneralRequest("fake", "E3", false))
        assertEquals(listOf(false), offers().map { it.rejected })
        val craft = tagCataloguesApi.createTagAbove(TagAboveRequest("fake", "E3", above.tags.map { it.key })).body!!
        assertEquals("Аппарат", craft.name)
        assertEquals(setOf(hulls, cargo), craft.children.map { it.key }.toSet())
        assertEquals(emptyList(), offers())
        tagsApi.relateTags(TagRelationRequest(craft.key, hulls, TagRelation.NONE))
        tagsApi.relateTags(TagRelationRequest(craft.key, cargo, TagRelation.NONE))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(craft.key, "fake", "E3", false))
        // A tag of the entry's name there already, bound to it not: that tag offered above both, not a new one
        tagsApi.nameTag(TagNameRequest(craft.key, "Аппарат"))
        val bind = offers().single()
        assertEquals(
            Triple(HierarchyOfferKind.BIND_PARENT, craft.key, setOf(hulls, cargo)),
            Triple(bind.kind, bind.existing?.key, bind.tags.map { it.key }.toSet()),
        )
        val boundAbove =
            tagCataloguesApi.createTagAbove(TagAboveRequest("fake", "E3", bind.tags.map { it.key }, craft.key)).body!!
        assertEquals(craft.key to listOf("E3"), boundAbove.key to boundAbove.catalogueEntries.map { it.entry })
        assertEquals(setOf(hulls, cargo), boundAbove.children.map { it.key }.toSet())
        // The hulls put under the cargo too: right under the craft over the cargo's head — offered to be taken away
        tagsApi.relateTags(TagRelationRequest(hulls, cargo, TagRelation.PARENT))
        val over = offers().single { it.kind == HierarchyOfferKind.UNLINK }
        assertEquals(listOf(hulls, craft.key, cargo), over.tags.map { it.key })
        tagsApi.relateTags(TagRelationRequest(hulls, cargo, TagRelation.NONE))
        tagsApi.relateTags(TagRelationRequest(craft.key, hulls, TagRelation.NONE))
        tagsApi.relateTags(TagRelationRequest(craft.key, cargo, TagRelation.NONE))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(craft.key, "fake", "E3", false))
        return craft.key
    }

    /** The offers of a new tag above others, or of a tag there to bind above them. */
    private fun newAbove() =
        offers().filter { it.kind == HierarchyOfferKind.NEW_PARENT || it.kind == HierarchyOfferKind.BIND_PARENT }

    /**
     * An entry said too general offers nothing above it; nothing offered above what a tag above already holds — the
     * [craft] above [hulls] and [cargo].
     */
    private fun tooGeneral(hulls: String, cargo: String, craft: String) {
        // A vessel said too general: the craft, more general still, offered above the hull and the sail no more
        assertEquals(listOf("E3"), newAbove().map { it.entry })
        tagCataloguesApi.judgeTagEntryTooGeneral(TooGeneralRequest("fake", "E2", true))
        assertEquals(emptyList(), newAbove())
        tagCataloguesApi.judgeTagEntryTooGeneral(TooGeneralRequest("fake", "E2", false))
        assertEquals(listOf("E3"), newAbove().map { it.entry })

        // A hull and a keel meet at the vessel: offered above them — but not once the craft, above the
        // vessel, is above the two already: the vessel would say nothing the craft does not
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E4", false))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E6", true))
        assertEquals(
            listOf("E2" to setOf(hulls, cargo)),
            newAbove().map { it.entry to it.tags.map { t -> t.key }.toSet() },
        )
        tagCataloguesApi.createTagAbove(TagAboveRequest("fake", "E3", listOf(hulls, cargo), craft))
        assertEquals(emptyList(), newAbove())
        tagsApi.relateTags(TagRelationRequest(craft, hulls, TagRelation.NONE))
        tagsApi.relateTags(TagRelationRequest(craft, cargo, TagRelation.NONE))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(craft, "fake", "E3", false))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(hulls, "fake", "E1", false))
        tagCataloguesApi.bindTagEntry(TagEntryRequest(cargo, "fake", "E6", false))
    }

    /** The rows of the tags alike [hulls]. */
    private fun rows(hulls: String) = tagsApi.listSimilarTags(hulls, null, 0, 50, null, null).body!!.tags

    /** What the tag [key] is to [hulls] among the tags alike it; none when it is not among them. */
    private fun relationOf(hulls: String, key: String) =
        rows(hulls).singleOrNull { it.tag.key == key && it.site == null }?.relation

    /** The values of the work [id]'s tags. */
    private fun tagsOf(id: String) =
        items.getItem("site", id).body!!.allFacets.single { it.facet == "tags" }.propertyValues

    /**
     * Both site's tags merged rows of [hulls]; one taken out, to a new tag of its own, the answer staying with the tag.
     * The key of the new tag.
     */
    private fun takenOut(hulls: String, starhulls: String): String {
        // Both site's tags merged rows of it; one taken out, to a new tag of its own; the answer stays with the tag
        fun rows() = rows(hulls)
        assertEquals(
            setOf("hulls", "starhulls"),
            rows()
                .filter { it.site != null }
                .onEach { assertEquals(TagRelation.MERGED, it.relation) }
                .map { it.site!!.tag }
                .toSet(),
        )
        val takenOut = tagsApi.bindSiteTag(TagBindingRequest("site", "starhulls")).body!!
        assertTrue(takenOut.key != hulls && takenOut.key != starhulls, "a new tag ${takenOut.key}")
        // Counted in the queue after the quiet — here at once
        inBackground { tagService.scope()?.let { tagRenaming.rename(it, it.facet) } }
        val out = tagsApi.getTag(takenOut.key).body!!
        assertEquals(2, out.works)
        assertEquals(listOf("hulls"), tagsApi.getTag(hulls).body!!.variants.map { it.tag })
        assertEquals(
            listOf(hulls to true),
            stores.source("site")!!.corrections.facetsOf("t3").filter { it.facet == "tags" }.map { it.key to it.added },
        )
        val other = out.key
        // One row of it among the alike, not merged
        assertEquals(
            listOf(TagRelation.NONE),
            rows().filter { it.tag.key == other && it.site == null }.map { it.relation },
        )
        return other
    }

    /**
     * Told apart from [hulls], the [other] tag stays among the alike, marked; the rows of a relation hidden, a page at
     * a time, searched for.
     */
    private fun toldApart(hulls: String, other: String) {
        // Told apart: stays among the alike, marked, whatever its likeness
        tagsApi.relateTags(TagRelationRequest(hulls, other, TagRelation.DIFFERENT))
        assertEquals(TagRelation.DIFFERENT, relationOf(hulls, other))
        // The rows of a relation hidden on the server, the total without them
        val all = tagsApi.listSimilarTags(hulls, null, 0, 50, null, null).body!!
        val noDifferent = tagsApi.listSimilarTags(hulls, null, 0, 50, null, listOf(TagRelation.DIFFERENT)).body!!
        assertTrue(
            noDifferent.tags.none { it.relation == TagRelation.DIFFERENT },
            "hidden ${noDifferent.tags.map { it.relation }}",
        )
        assertEquals(all.total - 1, noDifferent.total)
        // A page at a time: the pages one after another are the whole table
        val paged =
            (0 until all.total).chunked(1).flatMap {
                tagsApi.listSimilarTags(hulls, null, it.first(), 1, null, null).body!!.tags
            }
        assertEquals(all.tags.map { it.tag.key to it.site?.tag }, paged.map { it.tag.key to it.site?.tag })
        // Searched for: alike the text, not the tag — all the rows, in another order
        val searched = tagsApi.listSimilarTags(hulls, "freight cargo", 0, 50, null, null).body!!
        assertEquals(all.total, searched.total)
        assertTrue(searched.tags.zipWithNext().all { (a, b) -> a.similarity >= b.similarity }, "the likest first")
        tagsApi.relateTags(TagRelationRequest(hulls, other, TagRelation.NONE))
        assertEquals(TagRelation.NONE, rows(hulls).single { it.tag.key == other && it.site == null }.relation)
    }

    /**
     * Under a tag: a work with the [other], the child, has [hulls], the parent, shown as given by it; never under its
     * own descendant; the parent of a child confirmed not taken away.
     */
    private fun underATag(hulls: String, cargo: String, other: String) {
        // Under a tag: a work with the child has the parent, shown as given by it
        val hull = tagsApi.relateTags(TagRelationRequest(hulls, other, TagRelation.CHILD)).body!!
        assertEquals(listOf(other), hull.children.map { it.key })
        assertEquals(TagRelation.CHILD, relationOf(hulls, other))
        // The child confirmed: the parent given by it — unless the model gives it the work by itself
        corrections.correctFacet("site", "t2", FacetCorrection(facet = "tags", added = true, key = other))
        val parent = tagsOf("t2").single { it.key == hulls }
        assertTrue(parent.implied == true || (parent.chance ?: 0.0) > 0.5, "hulls of t2 $parent")
        assertTrue(parent.offered != true, "hulls of t2 $parent")
        // The tag above shown before the tag under it, whatever their chances
        val order = tagsOf("t2").map { it.key }
        assertTrue(order.indexOf(hulls) < order.indexOf(other), "the order of t2 $order")
        // A tag of several parents: under cargo too
        tagsApi.relateTags(TagRelationRequest(cargo, other, TagRelation.CHILD))
        assertEquals(setOf(hulls, cargo), tagsApi.getTag(other).body!!.parents.map { it.key }.toSet())
        tagsApi.relateTags(TagRelationRequest(cargo, other, TagRelation.NONE))
        // A tag never under its own descendant: other under hulls under cargo, and cargo not under other
        tagsApi.relateTags(TagRelationRequest(cargo, hulls, TagRelation.CHILD))
        assertEquals(
            Refusal.TAG_ABOVE,
            assertThrows<Refused> { tagsApi.relateTags(TagRelationRequest(other, cargo, TagRelation.CHILD)) }.refusal,
        )
        tagsApi.relateTags(TagRelationRequest(cargo, hulls, TagRelation.NONE))
        // The parent of a child the user confirmed is not taken away: the work has it by the child
        val implied =
            assertThrows<Refused> {
                corrections.correctFacet("site", "t2", FacetCorrection(facet = "tags", added = false, key = hulls))
            }
        assertEquals(Refusal.TAG_IMPLIED, implied.refusal)
        // The answer on the child taken back, the parent taken away: the child goes with it, not shown at all
        corrections.uncorrectFacet("site", "t2", "tags", other)
        corrections.correctFacet("site", "t2", FacetCorrection(facet = "tags", added = false, key = hulls))
        assertTrue(tagsOf("t2").none { it.key == other }, "tags ${tagsOf("t2")}")
        assertEquals(
            io.github.vlsergey.recommend4me.api.model.FacetValueInfo.Corrected.REMOVED,
            tagsOf("t2").single { it.key == hulls }.corrected,
        )
        // Nor offered among the candidates
        val candidates = suggestionsApi.getCandidates("site", "t2", "tags", null, null).body!!.map { it.key }
        assertTrue(other !in candidates, "candidates of t2 $candidates")
        // The other way round: above it
        assertEquals(
            listOf(other),
            tagsApi.relateTags(TagRelationRequest(hulls, other, TagRelation.PARENT)).body!!.parents.map { it.key },
        )
        tagsApi.relateTags(TagRelationRequest(hulls, other, TagRelation.NONE))
        assertEquals(TagRelation.NONE, relationOf(hulls, other) ?: TagRelation.NONE)
    }

    /**
     * A tag the user makes up on a work by its name: a new normalized tag of that name; the same name, the same tag.
     */
    private fun madeUp(hulls: String) {
        val type = stores.type("books")!!
        // A tag the user makes up on a work by its name: a new normalized tag of that name; the same name again, the
        // same tag
        // The answer is only written: it tells the key found or made, nothing of the work read back
        val made =
            corrections
                .correctFacet("site", "t1", FacetCorrection(facet = "tags", added = true, name = "Пустота"))
                .body!!
        val void = made.key
        assertEquals("Пустота", made.name)
        assertEquals(
            void,
            items
                .getItem("site", "t1")
                .body!!
                .allFacets
                .single { it.facet == "tags" }
                .propertyValues
                .single { it.name == "Пустота" }
                .key,
        )
        assertEquals("Пустота", type.tags.stats.explicitNames()[TagRepository.idOf(void)])
        corrections.correctFacet("site", "t3", FacetCorrection(facet = "tags", added = true, name = "пустота"))
        assertEquals(
            void,
            stores
                .source("site")!!
                .corrections
                .facetsOf("t3")
                .single { it.facet == "tags" && it.added && it.key != hulls }
                .key,
        )
    }
}
