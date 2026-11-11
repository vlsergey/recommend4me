package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.MarkAnswer
import io.github.vlsergey.recommend4me.api.model.MarkVerdict
import io.github.vlsergey.recommend4me.api.model.MarkingOrder
import io.github.vlsergey.recommend4me.api.model.SiteFandomBinding
import io.github.vlsergey.recommend4me.api.model.UniverseCharacters
import io.github.vlsergey.recommend4me.api.model.UniverseEntryBinding
import io.github.vlsergey.recommend4me.api.model.UniverseName
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import io.github.vlsergey.recommend4me.api.model.UniverseRelationRequest
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.SiteFandomRef
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * The universes of the site's works: the site's fandoms universes of their own, bound by name, merged and taken out by
 * the user; an entry of the catalogue found by its button made a universe whose characters are read, the characters and
 * the pairings worked out within them, drafted from the site's tags through the dictionary.
 */
@Order(2)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteUniversesTest : SiteTestBase() {

    /** The universe of the entry U2 of the catalogue, as the test that makes it made it. */
    private lateinit var u2: String

    @Test
    @Order(3)
    fun `an entry found by the button made a universe with its characters, and works are linked to it`() {
        capture("u1|Lone pilot|space|A pilot alone in orbit|")
        assertEquals(listOf("U1"), universeEntries.searchUniverses("Space").body!!.map { it.universe })
        val added = universes.addUniverse(UniverseRef("fake", "U1")).body!!
        val u1 = added.value
        assertEquals(listOf("fake:U1"), added.propertyEntries.map { it.value })
        assertEquals(true to 2, added.characters to added.characterCount)
        assertEquals(u1, universeEntries.searchUniverses("space").body!!.single().bound)
        // Added again, it is the same universe
        assertEquals(u1, universes.addUniverse(UniverseRef("fake", "U1")).body!!.value)
        assertEquals(
            listOf("Pilot", "The Pilot"),
            universeEntries.listCharacters("fake", "U1").body!!.single { it.character == "C1" }.names,
        )
        // The statements the site asked of the catalogue came with the characters, and are kept
        val pilot = stores.type("books")!!.universes.entries.characters("fake", "U1").single { it.id == "C1" }
        assertEquals(mapOf("P1" to listOf("Space Pilot")), pilot.properties)
        assertEquals(setOf("P1"), stores.type("books")!!.universes.findEntry("fake", "U1")!!.properties)

        // The station is said to be in the universe, but a place: shown, not kept as a character
        assertEquals(3, added.propertyEntries.single().total)
        val station = universeEntries.listCharacters("fake", "U1").body!!.single { it.character == "S1" }
        assertEquals(false, station.included)
        assertEquals(listOf("K2"), station.classes)
        val place = universeEntries.listUniverseClasses("fake", "U1").body!!.single { it.propertyClass == "K2" }
        assertEquals(Triple("place", false, 1), Triple(place.name, place.included, place.count))
        // The user keeps the places of the universe, then leaves this one out by itself, then takes both words back
        fun kept() = universes.getUniverse(u1).body!!.characterCount
        universeEntries.chooseUniverseClass("fake", "U1", "K2", Inclusion(true))
        assertEquals(3, kept())
        universeEntries.chooseUniverseEntry("fake", "U1", "S1", Inclusion(false))
        assertEquals(false, universeEntries.listCharacters("fake", "U1").body!!.single { it.character == "S1" }.choice)
        assertEquals(2, kept())
        universeEntries.resetUniverseEntry("fake", "U1", "S1")
        universeEntries.resetUniverseClass("fake", "U1", "K2")
        // The words stay over a refresh of the universe
        universeEntries.chooseUniverseEntry("fake", "U1", "S1", Inclusion(true))
        universes.addUniverse(UniverseRef("fake", "U1"))
        assertEquals(
            true,
            universeEntries.listCharacters("fake", "U1").body!!.single { it.character == "S1" }.included,
        )
        universeEntries.resetUniverseEntry("fake", "U1", "S1")

        // The universe is a value to pick for any work, linked to none yet; linked, it names itself by its entry
        assertTrue(searchController.listFacetValues("site", "universe", "space", 10).body!!.any { it.key == u1 })
        corrections.correctFacet("site", "u1", FacetCorrection(facet = "universe", added = true, key = u1))
        fun universeOfWork() =
            items
                .getItem("site", "u1")
                .body!!
                .allFacets
                .single { it.facet == "universe" }
                .propertyValues
                .find {
                    it.key == u1
                }
        assertEquals("Space opera", universeOfWork()?.name)

        // Its characters no more read: none kept is given to its works; read again, they are
        universes.chooseUniverseCharacters(UniverseCharacters(u1, false))
        assertEquals(false to 0, universes.getUniverse(u1).body!!.let { it.characters to it.characterCount })
        universes.chooseUniverseCharacters(UniverseCharacters(u1, true))
        assertEquals(2, kept())

        // Unbound from its entry, the entry goes with its characters; the universe and its works' links stay
        universes.bindUniverseEntry(UniverseEntryBinding(u1, "fake", "U1", false))
        assertTrue(universes.getUniverse(u1).body!!.propertyEntries.isEmpty())
        assertTrue(universeEntries.listCharacters("fake", "U1").body!!.isEmpty())
        assertNotNull(universeOfWork())
    }

    @Test
    @Order(4)
    fun `the site's fandoms are universes of their own, bound by name, merged and taken out by the user`() {
        capture("h1|Wand|magic|A wand||x1|hp:Harry Potter")
        capture("h2|Broom|magic|A broom||x2|hp-movies:harry potter")
        capture("h3|Ring|magic|A ring||x3|lotr:The Lord of the Rings")
        fun universeOf(key: String) =
            stores.type("books")!!.universes.fandoms.sites.fandomOf(SiteFandomRef("site", key))!!.toString()
        // Two fandoms of one name, any case, are one universe; drafted to their works at once, named by the fandom
        val hp = universeOf("hp")
        val lotr = universeOf("lotr")
        assertEquals(hp, universeOf("hp-movies"))
        assertNotEquals(hp, lotr)
        assertEquals("Harry Potter", valueOf("h1", "universe", hp)?.draftedFrom)
        assertEquals("harry potter", valueOf("h2", "universe", hp)?.draftedFrom)
        assertEquals("Harry Potter", universes.getUniverse(hp).body!!.name)

        // The user's answer on one; merged into another, its fandoms and the answer are the other's
        corrections.correctFacet("site", "h3", FacetCorrection(facet = "universe", added = true, key = lotr))
        universes.relateUniverses(UniverseRelationRequest(hp, lotr, UniverseRelation.MERGED))
        assertTrue(universes.listUniverses().body!!.none { it.value == lotr })
        assertEquals(hp, universeOf("lotr"))
        assertEquals(3, universes.getUniverse(hp).body!!.sites.size)
        assertTrue(
            stores.source("site")!!.corrections.facetsOf("h3").any { it.facet == "universe" && it.key == hp },
            "h3 ${stores.source("site")!!.corrections.facetsOf("h3")}",
        )

        // A site's fandom taken out is a universe of its own; put under another, its works are of the other too
        val ring = universes.bindSiteFandom(SiteFandomBinding("site", "lotr")).body!!.value
        assertNotEquals(hp, ring)
        universes.relateUniverses(UniverseRelationRequest(hp, ring, UniverseRelation.CHILD))
        assertEquals(listOf(hp), universes.getUniverse(ring).body!!.parents)
        inBackground { layerDrafts.redraftPlanned() }
        assertNotNull(valueOf("h3", "universe", ring)?.draftedFrom)
        assertNotNull(valueOf("h3", "universe", hp))
        // A word on the two in place of the word before; one under a universe of its own descendants refused: a loop
        universes.relateUniverses(UniverseRelationRequest(ring, hp, UniverseRelation.CHILD))
        assertEquals(
            listOf(hp) to emptyList<String>(),
            universes.getUniverse(ring).body!!.let { it.children to it.parents },
        )
        // A site's fandom put down: a universe of its own under the one it leaves
        val ofHp = universes.bindSiteFandom(SiteFandomBinding("site", "hp-movies", under = true)).body!!.value
        assertEquals(listOf(hp), universes.getUniverse(ofHp).body!!.parents)
        val loop =
            assertFailsWith<Refused> {
                universes.relateUniverses(UniverseRelationRequest(ofHp, ring, UniverseRelation.CHILD))
            }
        assertEquals(Refusal.UNIVERSE_ABOVE, loop.refusal)
        universes.relateUniverses(UniverseRelationRequest(hp, ofHp, UniverseRelation.MERGED))
        assertEquals(hp, universeOf("hp-movies"))

        // A name of the user's over the site's
        universes.nameUniverse(UniverseName(hp, "Мир ГП"))
        assertEquals("Мир ГП", universes.getUniverse(hp).body!!.name)
        universes.nameUniverse(UniverseName(hp, null))
        assertEquals("Harry Potter", universes.getUniverse(hp).body!!.name)

        // What the site says each fandom is of stands with it; a page that said nothing gives none
        stores
            .source("site")!!
            .items
            .describeFacetValues("fandom", mapOf("hp" to "By J. K. Rowling's books", "hp-movies" to ""))
        val said = universes.getUniverse(hp).body!!.sites.associate { it.key to it.description }
        assertEquals("By J. K. Rowling's books", said["hp"])
        assertNull(said["hp-movies"])

        // An original work has no universe of its fandom
        capture("h4|Mine|magic|My own world||x4|originals:Originals")
        assertTrue(
            facetsOf("h4").find { it.facet == "universe" }?.propertyValues.orEmpty().none { it.draftedFrom != null }
        )
    }

    @Test
    @Order(1)
    fun `the characters and the pairings of a work are worked out within its universes`() {
        u2 = universes.addUniverse(UniverseRef("fake", "U2")).body!!.value
        // Works of the universe, the site's line naming its pairing, and works of no universe
        repeat(12) { k ->
            capture("p$k|Pilots $k|space|Pilots fly starships in orbit near planets $k|Pilot/Captain")
            capture("q$k|Knights $k|dragons|A knight guards the princess in the castle $k|Knight/Princess")
        }
        repeat(12) { k ->
            corrections.correctFacet("site", "p$k", FacetCorrection(facet = "universe", added = true, key = u2))
        }
        // The user's word on half of them
        repeat(6) { k ->
            listOf("fake:C1", "fake:C2").forEach {
                corrections.correctFacet("site", "p$k", FacetCorrection(facet = "characters", added = true, key = it))
            }
            corrections.correctFacet(
                "site",
                "p$k",
                FacetCorrection(facet = "pairings", added = true, key = "pair:fake:C1|fake:C2"),
            )
        }
        val store = stores.source("site")!!
        refresh(store)
        inBackground { suggestions.refresh(store) }
        // The pairings are made of the characters the model gave: once more, over what it gave
        inBackground { suggestions.refresh(store) }

        val facets = items.getItem("site", "p9").body!!.allFacets
        charactersOfAWork(facets)
        noUniverseNoCharacters(facets)
        newWorkOffered(store)
        mainOrSecondary()
        originalCharactersAndPairs()
        universeOfFanFiction(store)
        noUniverseOfAnother(store)
        ownNames()
    }

    /** The characters and the pairing of a work of the universe ([facets] its facets), worked out by the model. */
    private fun charactersOfAWork(facets: List<ItemFacet>) {
        val characters = facets.single { it.facet == "characters" }.propertyValues
        assertEquals(
            setOf("fake:C1", "fake:C2"),
            characters.filter { it.inferred == true }.map { it.key }.toSet(),
            "characters $characters",
        )
        assertEquals("Pilot", characters.single { it.key == "fake:C1" }.name)
        val pairing = facets.single { it.facet == "pairings" }.propertyValues.single { it.offered != true }
        assertEquals("pair:fake:C1|fake:C2", pairing.key)
        assertEquals("Pilot / Captain", pairing.name)
        // Beside the two it gives, the likeliest other character is offered: three to answer on at least
        val unanswered = characters.filter { it.corrected == null }
        assertEquals(1, unanswered.count { it.offered == true }, "characters $characters")
        assertTrue(unanswered.size >= 3, "characters $characters")
    }

    /**
     * A universe the model gives a work lets it have the universe's characters; said to have no universe, a work has
     * none of one, its site's line shown above ([facets] the facets of a work of the universe).
     */
    private fun noUniverseNoCharacters(facets: List<ItemFacet>) {
        // A universe the model gives a work — more likely than not — lets it have the universe's characters, confirmed
        // or not
        fun outside() =
            items.getItem("site", "q9").body!!.allFacets.filter { it.facet == "characters" || it.facet == "pairings" }
        val givenUniverse =
            items
                .getItem("site", "q9")
                .body!!
                .allFacets
                .single { it.facet == "universe" }
                .propertyValues
                .any { it.key == u2 && it.inferred == true }
        if (givenUniverse) {
            val chosen =
                outside().single { it.facet == "characters" }.propertyValues.map { it.key } +
                    suggestionsApi.getCandidates("site", "q9", "characters", null, null).body!!.map { it.key }
            assertTrue(chosen.any { it.startsWith("fake:C") }, "characters of q9 $chosen")
        }
        // Said to have no universe, a work has no characters of one: only its site's line, and the original characters
        // offered
        corrections.completeFacet("site", "q9", "universe")
        val none = outside()
        assertTrue(
            none.all { f ->
                f.propertyValues.all { it.offered == true } &&
                    f.said.orEmpty().flatMap { it.words }.map { it.name } == listOf("Knight/Princess")
            },
            "outside $none",
        )
        // Its site's line is shown above
        assertEquals(
            listOf("Pilot/Captain"),
            facets.single { it.facet == "characters" }.said.orEmpty().flatMap { it.words }.map { it.name },
        )
    }

    /**
     * A new work of the universe: every character it may have offered, with how often its texts name them; "…" the
     * likeliest and the most named.
     */
    private fun newWorkOffered(store: SourceStore) {
        // A new work of the universe: every character it may have is offered, with how often its texts name them
        // A name of one word is a name written with a capital: the lower-case "captain" names nobody
        capture(
            "n1|The captain|space|The Captain commands. The Captain waits. " +
                "The Captain sleeps, the captain of the guard does not|"
        )
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "universe", added = true, key = u2))
        refresh(store)
        // The models fitted after the quiet — here at once; how often its texts name every character is counted for its
        // candidates alone
        inBackground { suggestions.refresh(store) }
        // The candidates wait for nothing: they offer what the weights say now, the work's kept chances
        // are made in the queue of the computations; a value the card gives is never offered beside it
        val offered = suggestionsApi.getCandidates("site", "n1", "characters", null, null).body!!
        val given =
            items
                .getItem("site", "n1")
                .body!!
                .allFacets
                .find { it.facet == "characters" }
                ?.propertyValues
                .orEmpty()
                .filter { it.offered != true }
                .map { it.key }
        assertEquals(
            setOf("fake:C1", "fake:C2", "oc:female", "oc:male"),
            (offered.map { it.key } + given).toSet(),
            "offered $offered, given $given",
        )
        assertTrue(offered.none { it.key in given }, "offered $offered, given $given")
        assertTrue(offered.zipWithNext().all { (a, b) -> a.chance!! >= b.chance!! }, "the likeliest first: $offered")
        offered.find { it.key == "fake:C2" }?.let { assertEquals(3, it.mentions) }
        // Given or offered as the model's chance falls (above): when offered, no text of the work names it
        offered.find { it.key == "oc:male" }?.let { assertEquals(0, it.mentions) }
        assertEquals("Captain", offered.find { it.key == "fake:C2" }?.name ?: "Captain")
        // "…": the likeliest so many, then so many more of the rest the texts name three times at least, the most named
        // first, each once
        val more = suggestionsApi.getCandidates("site", "n1", "characters", 1, 2).body!!
        val byText =
            offered.drop(1).filter { (it.mentions ?: 0) >= 3 }.sortedByDescending { it.mentions }.take(2).map { it.key }
        assertEquals(offered.take(1).map { it.key } + byText, more.map { it.key }, "more $more of $offered")
        assertEquals(listOf(false) + byText.map { true }, more.map { it.named == true }, "more $more")
        // The passages naming a character, each by where it is: the annotation by the key of its label
        val quotes = suggestionsApi.getMentions("site", "n1", "characters", "fake:C2").body!!
        assertTrue(
            quotes.isNotEmpty() &&
                quotes.all { it.labelKey == "site:text.annotation" && it.title == null && it.part == null },
            "quotes $quotes",
        )
        assertEquals(404, suggestionsApi.getCandidates("site", "n1", "nope", null, null).statusCode.value())
    }

    /** The keys of the values of the work n1 of a [facet]. */
    private fun keysOf(facet: String) =
        items.getItem("site", "n1").body!!.allFacets.find { it.facet == facet }?.propertyValues.orEmpty().map { it.key }

    /** The keys of the candidates of the work n1 of a [facet]. */
    private fun candidatesOf(facet: String) =
        suggestionsApi.getCandidates("site", "n1", facet, null, null).body!!.map { it.key }

    /** A character is a work's main one or its secondary one: never shown, given or offered in both. */
    private fun mainOrSecondary() {
        // A character is a work's main one or its secondary one: never shown, given or offered in both
        // No work has a secondary character yet: nothing to learn from, every character of the universe offered all the
        // same
        val untaught = suggestionsApi.getCandidates("site", "n1", "secondary-characters", null, null).body!!
        assertTrue(untaught.isNotEmpty() && untaught.all { it.chance == null }, "secondary $untaught")
        // Each with every name it goes by, the shown one first: a search finds it by any of them
        assertEquals(listOf("Pilot", "The Pilot"), untaught.first { it.key == "fake:C1" }.names)
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "fake:C1"))
        assertTrue(
            "fake:C1" !in keysOf("secondary-characters") && "fake:C1" !in candidatesOf("secondary-characters"),
            "secondary ${keysOf("secondary-characters")}",
        )
        assertTrue(
            "fake:C2" in keysOf("secondary-characters") + candidatesOf("secondary-characters") ||
                "fake:C2" in keysOf("characters")
        )
        // Said secondary, it leaves the main ones — the model's word yields to the user's
        val secondary =
            corrections.correctFacet(
                "site",
                "n1",
                FacetCorrection(facet = "secondary-characters", added = true, key = "fake:C2"),
            )
        assertTrue(
            "fake:C2" !in keysOf("characters") && "fake:C2" !in candidatesOf("characters"),
            "main ${keysOf("characters")}",
        )
        // The answer tells the page of both facets: the main ones show it no more
        val facets = secondary.body!!.facets.associateBy { it.facet }
        assertEquals(setOf("secondary-characters", "characters"), facets.keys)
        assertTrue("fake:C2" in facets.getValue("characters").hidden, "main ${facets["characters"]}")
        // Said main then, it is secondary no more: the earlier answer is taken back, not shown crossed out
        val main =
            corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "fake:C2"))
        assertTrue(
            "fake:C2" in keysOf("characters") && "fake:C2" !in keysOf("secondary-characters"),
            "main ${keysOf("characters")}, secondary ${keysOf("secondary-characters")}",
        )
        val after = main.body!!.facets.associateBy { it.facet }.getValue("secondary-characters")
        assertTrue(after.answers.none { it.key == "fake:C2" } && "fake:C2" in after.hidden, "secondary $after")
    }

    /** The original characters, many persons, main and secondary both; every pair of the main ones given or offered. */
    private fun originalCharactersAndPairs() {
        // The original characters are many persons: in both
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "oc:female"))
        assertTrue("oc:female" in keysOf("secondary-characters") + candidatesOf("secondary-characters"))
        // Shown by the key of its words; its name, which the model reads, stays one language
        val ofc =
            items
                .getItem("site", "n1")
                .body!!
                .allFacets
                .single { it.facet == "characters" }
                .propertyValues
                .single { it.key == "oc:female" }
        assertEquals("standard:character.oc-female", ofc.nameKey)
        assertEquals(UniverseFacet.ORIGINAL_CHARACTERS.getValue("oc:female"), ofc.name)
        // Every pair of the work's main characters is given or offered, a pair of characters added after the
        // fitting too: the model has no chance of it, offered all the same
        val had =
            items
                .getItem("site", "n1")
                .body!!
                .allFacets
                .single { it.facet == "characters" }
                .propertyValues
                .filter { it.offered != true && it.corrected != FacetValueInfo.Corrected.REMOVED }
                .map { it.key }
        val mains = (had + UniverseFacet.ORIGINAL_CHARACTERS.keys).distinct().sorted()
        val pairs = mains.flatMapIndexed { k, a -> mains.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
        assertTrue("pair:fake:C2|oc:female" in pairs, "pairs $pairs")
        val pairingsShown = keysOf("pairings") + candidatesOf("pairings")
        assertTrue(pairingsShown.containsAll(pairs), "pairings $pairingsShown, pairs $pairs")
    }

    /**
     * The universe of a fan fiction worked out as its characters are; its universes all named, no other offered, till
     * taken back.
     */
    private fun universeOfFanFiction(store: SourceStore) {
        // The universe of a fan fiction is worked out as its characters are: given when likely, offered first when not
        capture("f1|Fleet tales|space|Ships of the fleet|")
        corrections.correctFacet("site", "f1", FacetCorrection(facet = "kind", added = true, key = "fanfiction"))
        refresh(store)
        inBackground { suggestions.refresh(store) }
        val universes =
            items.getItem("site", "f1").body!!.allFacets.single { it.facet == "universe" }.propertyValues.map { it.key }
        val offeredUniverses = suggestionsApi.getCandidates("site", "f1", "universe", null, null).body!!.map { it.key }
        assertEquals(u2, (universes + offeredUniverses).first(), "given $universes, offered $offeredUniverses")

        // Its universes all named: no other is offered, the work has its own alone
        val u1 = this.universes.addUniverse(UniverseRef("fake", "U1")).body!!.value
        // A universe new to the dictionary is among the values a work is scored by once the facet is
        // fitted again, after the quiet — here at once: the scoring kept is of the values it was fitted on
        inBackground { suggestions.refresh(store) }
        // The other universe: given by the model or offered among the candidates
        fun other(details: io.github.vlsergey.recommend4me.api.model.ItemDetails) =
            details.allFacets.single { it.facet == "universe" }.propertyValues.any { it.key == u1 } ||
                suggestionsApi.getCandidates("site", "f1", "universe", null, null).body!!.any { it.key == u1 }
        // An answer is kept at once; the chances it changes are made as the work is read
        fun settled(item: String) = items.getItem("site", item).body!!
        corrections.correctFacet("site", "f1", FacetCorrection(facet = "universe", added = true, key = u2))
        assertTrue(other(settled("f1")))
        corrections.completeFacet("site", "f1", "universe")
        val named = settled("f1").allFacets.single { it.facet == "universe" }
        assertEquals(true, named.complete)
        assertEquals(
            listOf(u2),
            named.propertyValues.filter { it.corrected != FacetValueInfo.Corrected.REMOVED }.map { it.key },
            "values ${named.propertyValues}",
        )
        assertEquals(
            emptyList(),
            suggestionsApi.getCandidates("site", "f1", "universe", null, null).body!!.map { it.key },
        )
        // Taken back, the others are offered again
        corrections.uncompleteFacet("site", "f1", "universe")
        val reopened = settled("f1")
        assertNull(reopened.allFacets.single { it.facet == "universe" }.complete)
        assertTrue(other(reopened))
        this.universes.bindUniverseEntry(UniverseEntryBinding(u1, "fake", "U1", false))
    }

    /** An original work, an alternative history and a realistic one have no universe of another's. */
    private fun noUniverseOfAnother(store: SourceStore) {
        // An original work, an alternative history and a realistic one have no universe of another's
        capture("o1|Our own world|space|Ships of the fleet|")
        corrections.correctFacet(
            "site",
            "o1",
            FacetCorrection(facet = "kind", added = true, key = "alternative-history"),
        )
        capture("o2|A school year|school|Lessons and friends|")
        corrections.correctFacet("site", "o2", FacetCorrection(facet = "kind", added = true, key = "realism"))
        // A standard value is shown by its key
        val kind =
            items
                .getItem("site", "o1")
                .body!!
                .allFacets
                .single { it.facet == "kind" }
                .propertyValues
                .single { it.key == "alternative-history" }
        assertEquals("standard:facet.kind.value.alternative-history", kind.nameKey)
        assertTrue(
            filtersController.listFilterValues("books", "kind").body!!.propertyValues.any {
                it.nameKey == "standard:facet.kind.value.realism"
            }
        )
        inBackground { suggestions.refresh(store) }
        assertEquals(
            emptyList(),
            suggestionsApi.getCandidates("site", "o1", "universe", null, null).body!!.map { it.key },
        )
        assertEquals(
            emptyList(),
            suggestionsApi.getCandidates("site", "o2", "universe", null, null).body!!.map { it.key },
        )
    }

    /** The user's own name of a character and of a universe over the catalogue's: shown everywhere in its place. */
    private fun ownNames() {
        // The user's own name of a character and of a universe over the catalogue's: shown everywhere in its place
        fun characterName() =
            items
                .getItem("site", "p9")
                .body!!
                .allFacets
                .single { it.facet == "characters" }
                .propertyValues
                .single { it.key == "fake:C1" }
                .name
        this.universeEntries.nameUniverseEntry("fake", "C1", LocalName("Лётчица"))
        assertEquals("Лётчица", characterName())
        // Kept as the universe is read again from the catalogue, its characters made anew
        this.universes.addUniverse(UniverseRef("fake", "U2"))
        assertEquals("Лётчица", characterName())
        assertEquals(
            listOf("Лётчица", "Pilot"),
            this.universeEntries.listCharacters("fake", "U2").body!!.single { it.character == "C1" }.names.take(2),
        )
        this.universeEntries.nameUniverseEntry("fake", "U2", LocalName("Флот"))
        fun fleet() = this.universes.listUniverses().body!!.single { u -> u.propertyEntries.any { it.entry == "U2" } }
        assertEquals("Флот" to "Fleet", fleet().name to fleet().propertyEntries.single().catalogueName)
        // Taken back: the catalogue's names again
        this.universeEntries.nameUniverseEntry("fake", "C1", LocalName(null))
        this.universeEntries.nameUniverseEntry("fake", "U2", LocalName(""))
        assertEquals("Pilot", characterName())
        assertNull(fleet().propertyEntries.single().localName)
    }

    @Test
    @Order(2)
    fun `characters and universe drafted from the site's tags by the dictionary, the user's word over them`() {
        // The dictionary without the entry: nothing to draft from
        universes.listUniverses().body!!.forEach { u ->
            u.propertyEntries.forEach {
                universes.bindUniverseEntry(UniverseEntryBinding(u.value, it.catalogue, it.entry, false))
            }
        }
        capture("g1|Wingmen|space|Two friends fly a starship|Space Pilot & Captain")
        assertNull(valueOf("g1", "characters", "fake:C1"))

        // The universe added: its pilot is stated to be the site's "Space Pilot" — drafted a main character
        // of the work captured before, and of one captured now; the universe with it, the pilot's alone
        u2 = universes.addUniverse(UniverseRef("fake", "U2")).body!!.value
        // The works of the source drafted again in the background, the work captured now at once
        inBackground { layerDrafts.redraftPlanned() }
        capture("g2|Wingmen again|space|The friends fly once more|Space Pilot & Captain")
        listOf("g1", "g2").forEach { id ->
            val pilot = assertNotNull(valueOf(id, "characters", "fake:C1"), "characters of $id: ${facetsOf(id)}")
            assertEquals("Space Pilot", pilot.draftedFrom)
            assertNull(pilot.inferred)
            assertEquals("Space Pilot", valueOf(id, "universe", u2)?.draftedFrom, "universe of $id")
        }
        // The captain has no tag of the site in the catalogue: not drafted
        assertNull(valueOf("g1", "characters", "fake:C2")?.draftedFrom)
        // Drafted of the layer, not of the site: kept in the model's database
        assertEquals(
            setOf(UniverseFacets.CHARACTERS to "fake:C1", UniverseFacets.UNIVERSE to u2),
            stores.source("site")!!.drafts.ofItems(listOf("g1")).getValue("g1").map { it.facet to it.key }.toSet(),
        )
        val pairing = pairingOfTheDictionary()
        pairingAnswered(pairing)
        everyOrder(pairing)
        draftsFollowThePage()
    }

    /** The facets of the work [id]. */
    private fun facetsOf(id: String) = items.getItem("site", id).body!!.allFacets

    /** The value [key] of the [facet] of the work [id]; none when the work has it not. */
    private fun valueOf(id: String, facet: String, key: String) =
        facetsOf(id).find { it.facet == facet }?.propertyValues?.find { it.key == key }

    /** The works offered to mark by the [pairing] — those [answered] on, or not — in the [order] asked. */
    private fun toMark(pairing: String, answered: Boolean = false, order: MarkingOrder = MarkingOrder.LIKELY) =
        marking.getMarkingWorks(UniverseFacets.PAIRINGS, pairing, answered, order, 0, 50).body!!.works

    /**
     * A pairing of the dictionary's characters, by its key alone — none kept; the works of its universe offered to mark
     * by it. Its key.
     */
    private fun pairingOfTheDictionary(): String {
        // A pairing of the dictionary's characters, by its key alone — none kept; the works of its
        // universe offered to mark by it, the user's three words on them
        val made = pairingsApi.getPairing(UniverseFacets.pairing("fake:C2", "fake:C1")).body!!
        assertEquals(UniverseFacets.pairing("fake:C1", "fake:C2"), made.key)
        assertEquals(setOf("Pilot", "Captain"), made.characters.map { it.name }.toSet())
        assertTrue(
            toMark(made.key).map { it.item }.containsAll(listOf("g1", "g2")),
            "to mark: ${toMark(made.key).map { it.item }}",
        )
        assertEquals(listOf("Space Pilot & Captain"), toMark(made.key).first { it.item == "g1" }.lines.map { it.line })
        return made.key
    }

    /**
     * The user's three words on the works of the [pairing]: yes, the pairing the work's, its two characters its main
     * ones; not clear, nothing written, asked no more; no, answered so.
     */
    private fun pairingAnswered(pairing: String) {
        // Yes: the pairing the work's, its two characters its main ones; not clear: nothing written, asked no more
        fun counted() = pairingsApi.getPairing(pairing).body!!.let { Triple(it.yes, it.no, it.unknown) }
        val before = counted()
        marking.answerMarking(MarkAnswer("site", "g1", UniverseFacets.PAIRINGS, pairing, MarkVerdict.YES))
        marking.answerMarking(MarkAnswer("site", "g2", UniverseFacets.PAIRINGS, pairing, MarkVerdict.UNKNOWN))
        val g1 =
            stores.source("site")!!.corrections.facetsOf("g1").filter { it.added }.map { it.facet to it.key }.toSet()
        assertTrue(
            g1.containsAll(
                setOf(
                    UniverseFacets.PAIRINGS to pairing,
                    UniverseFacets.CHARACTERS to "fake:C1",
                    UniverseFacets.CHARACTERS to "fake:C2",
                )
            ),
            "g1: $g1",
        )
        assertTrue(stores.source("site")!!.corrections.facetsOf("g2").none { it.facet == UniverseFacets.PAIRINGS })
        assertTrue(toMark(pairing).none { it.item in setOf("g1", "g2") })
        assertEquals(
            mapOf("g1" to MarkVerdict.YES, "g2" to MarkVerdict.UNKNOWN),
            toMark(pairing, answered = true).filter { it.item in setOf("g1", "g2") }.associate { it.item to it.answer },
        )
        assertEquals(Triple(before.first + 1, before.second, before.third + 1), counted())
        // No: answered so, the characters as they were
        marking.answerMarking(MarkAnswer("site", "g2", UniverseFacets.PAIRINGS, pairing, MarkVerdict.NO))
        assertEquals(
            listOf(false),
            stores
                .source("site")!!
                .corrections
                .facetsOf("g2")
                .filter { it.facet == UniverseFacets.PAIRINGS }
                .map { it.added },
        )
        assertTrue(stores.source("site")!!.corrections.facetsOf("g2").none { it.facet == UniverseFacets.CHARACTERS })
    }

    /** Every order of the works of the [pairing] shows the same works, each as it was answered on; taken back. */
    private fun everyOrder(pairing: String) {
        // Every order shows the same works, each as it was answered on
        MarkingOrder.entries.forEach { order ->
            assertEquals(
                toMark(pairing).map { it.item }.toSet(),
                toMark(pairing, order = order).map { it.item }.toSet(),
                "open, $order",
            )
            assertEquals(
                mapOf("g1" to MarkVerdict.YES, "g2" to MarkVerdict.NO),
                toMark(pairing, true, order).filter { it.item in setOf("g1", "g2") }.associate { it.item to it.answer },
                "answered, $order",
            )
        }
        // Taken back: the works to mark again, as they were before
        listOf("g1", "g2").forEach { marking.unanswerMarking("site", it, UniverseFacets.PAIRINGS, pairing) }
        listOf("fake:C1", "fake:C2").forEach { corrections.uncorrectFacet("site", "g1", UniverseFacets.CHARACTERS, it) }
        assertTrue(toMark(pairing).map { it.item }.containsAll(listOf("g1", "g2")))
    }

    /**
     * The drafts follow the site's page; the user's word over them — a value taken away stays so, a yes is the user's;
     * the universe gone, its drafts go.
     */
    private fun draftsFollowThePage() {
        // The site's page changes: the drafts follow it — the pilot no more on the line, no more drafted
        capture("g3|Wingmen third|space|They fly a third time|Space Pilot & Captain")
        assertEquals("Space Pilot", valueOf("g3", "characters", "fake:C1")?.draftedFrom)
        capture("g3|Wingmen third|space|They fly a third time|Captain & Navigator")
        assertNull(valueOf("g3", "characters", "fake:C1")?.draftedFrom)
        assertTrue(stores.source("site")!!.drafts.ofItems(listOf("g3")).values.all { it.isEmpty() })

        // The user takes the pilot away: taken away it stays, whatever the dictionary does
        corrections.correctFacet("site", "g1", FacetCorrection(facet = "characters", added = false, key = "fake:C1"))
        universes.addUniverse(UniverseRef("fake", "U2"))
        val removed = assertNotNull(valueOf("g1", "characters", "fake:C1"))
        assertEquals(FacetValueInfo.Corrected.REMOVED, removed.corrected)
        assertNull(removed.draftedFrom)

        // The user's yes is the user's word: shown as confirmed, no more a draft
        corrections.correctFacet("site", "g2", FacetCorrection(facet = "characters", added = true, key = "fake:C1"))
        val confirmed = assertNotNull(valueOf("g2", "characters", "fake:C1"))
        assertEquals(FacetValueInfo.Corrected.CONFIRMED, confirmed.corrected)
        assertNull(confirmed.draftedFrom)

        // The entry gone, the drafts of it go too — what the model learnt of it is the model's to work out again
        universes.bindUniverseEntry(UniverseEntryBinding(u2, "fake", "U2", false))
        inBackground { layerDrafts.redraftPlanned() }
        assertNull(valueOf("g2", "universe", u2)?.draftedFrom)
        assertTrue(stores.source("site")!!.drafts.ofItems(listOf("g1", "g2")).values.all { it.isEmpty() })
    }
}
