package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.task.inBackground
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A work its site says is an original, never marked by the user, given a universe whose characters are read: the
 * characters of the universe are then to be chosen on its screen and on the site's page alike — the model's chances of
 * them by the rest of the work, and every one of them to be found by its name in the field of the characters.
 */
// More than a few seconds — models fitted: in CI alone (user, 2026-11-18)
@Tag("ci")
class SiteOriginalToUniverseTest : SiteTestBase() {
    companion object {
        /** The site of this class alone: its models of its own works. */
        const val SITE = "originalsite"

        /** The works of the universe, and of none, the models are fitted of. */
        const val WORKS = 12

        /** The user's word that a work has a value. */
        val USERS = setOf(FacetValueInfo.Corrected.ADDED, FacetValueInfo.Corrected.CONFIRMED)

        /** The characters of the universe the work is given, as values. */
        val CHARACTERS = setOf("fake:C1", "fake:C2")
    }

    override val site = SITE

    // The characters are learnt by the bilinear suggester, as the application's own
    @BeforeEach fun bilinear() = learntBy(BILINEAR)

    @AfterEach fun cheapest() = learntBy(null)

    @Test
    fun `an original given a universe has its characters to choose, by their chances and by their names`() {
        val universe = taught()
        // A work its site says is an original, no word of the user's on it
        // — of words of neither the universe's works nor the others': the universe is the user's word, not the model's
        capture("o1|The courier|letters|A courier carries letters across the desert|||originals:Originals")
        val before = items.getItem(site, "o1").body!!.allFacets
        assertTrue(before.all { f -> f.propertyValues.all { it.corrected == null } }, "unmarked $before")
        val guessed = before.single { it.facet == UniverseFacets.UNIVERSE }.propertyValues
        assertTrue(guessed.none { it.key == universe && it.likely == true }, "the model's universe $guessed")
        val kind = before.single { it.facet == UniverseFacets.KIND }.propertyValues
        // The site's own value: neither the user's nor the model's
        assertEquals(listOf(UniverseFacets.ORIGINAL), kind.filter { it.inferred != true }.map { it.key }, "kind $kind")

        // The user gives it the universe: the answer kept, the work read anew at once
        corrections.correctFacet(site, "o1", FacetCorrection(facet = "universe", added = true, key = universe))
        charactersToChoose(universe, "as the answer is kept")
        // And after the background did what the answer planned
        background.run()
        charactersToChoose(universe, "after the background")
    }

    /**
     * Works of the universe [UniverseRef] U2 whose characters the user named, and works of none, the models fitted of
     * them: the universe's value.
     */
    private fun taught(): String {
        val universe = universes.addUniverse(UniverseRef("fake", "U2")).body!!.value
        assertTrue(universes.getUniverse(universe).body!!.characters, "the universe's characters are read")
        repeat(WORKS) { k ->
            capture("p$k|Pilots $k|space|Pilots fly starships in orbit near planets $k|")
            capture("q$k|Knights $k|dragons|A knight guards the princess in the castle $k|")
            corrections.correctFacet(site, "p$k", FacetCorrection(facet = "universe", added = true, key = universe))
        }
        repeat(WORKS / 2) { k ->
            CHARACTERS.forEach {
                corrections.correctFacet(site, "p$k", FacetCorrection(facet = "characters", added = true, key = it))
            }
            corrections.completeFacet(site, "p$k", "characters")
            corrections.completeFacet(site, "q$k", "characters")
        }
        val store = stores.source(site)!!
        refresh(store)
        inBackground { suggestions.refresh(store) }
        return universe
    }

    /** On the work's screen and on the site's page, the characters of the [universe] to choose ([when] it is so). */
    private fun charactersToChoose(universe: String, `when`: String) {
        val screen = items.getItem(site, "o1").body!!.allFacets
        val page = pages.getPage("https://$site.example/work/o1").body!!.item!!.facets
        listOf("the screen" to screen, "the page" to page).forEach { (where, facets) ->
            val given = facets.single { it.facet == UniverseFacets.UNIVERSE }.propertyValues
            assertTrue(
                // The user's word: added, or confirmed of the model's that gave it already
                given.any { it.key == universe && it.corrected in USERS },
                "$where, $`when`: the universe $given",
            )
            assertTrue(facets.any { it.facet == UniverseFacets.CHARACTERS }, "$where, $`when`: no field of characters")
        }
        chances(screen, `when`)
        foundOnTheScreen(universe, `when`)
        foundOnThePage(`when`)
    }

    /**
     * The model's chance of every character of the universe, by the rest of the work: given on the card ([screen]) or
     * offered among the candidates the line of the characters and "…" read.
     */
    private fun chances(screen: List<ItemFacet>, `when`: String) {
        val given =
            screen
                .single { it.facet == UniverseFacets.CHARACTERS }
                .propertyValues
                .filter { it.key in CHARACTERS && it.chance != null }
                .map { it.key }
        val offered =
            suggestionsApi
                .getCandidates(site, "o1", UniverseFacets.CHARACTERS, null, null)
                .body!!
                .filter { it.key in CHARACTERS && it.chance != null }
                .map { it.key }
        assertEquals(CHARACTERS, (given + offered).toSet(), "$`when`: the chances given $given, offered $offered")
    }

    /**
     * The screen's field of the characters ([io.github.vlsergey.recommend4me.universe] `CharacterField`): the work's
     * universes as the list of the universes tells them, every entry whose characters are read, its characters kept —
     * what the search of the field finds among.
     */
    private fun foundOnTheScreen(universe: String, `when`: String) {
        val linked = universes.listUniverses().body!!.single { it.value == universe }
        assertTrue(linked.characters, "$`when`: the universe's characters are read")
        val found =
            linked.propertyEntries
                .flatMap { e -> universeEntries.listCharacters(e.catalogue, e.entry).body!! }
                .filter { it.included }
        assertTrue(found.map { it.value }.containsAll(CHARACTERS), "$`when`: the screen's search among $found")
        assertTrue(found.any { c -> c.value == "fake:C1" && c.names.any { "pilot" in it.lowercase() } })
    }

    /**
     * The page's field of the characters (`addField` of the extension): the candidates of the work, every one, found by
     * any of its names.
     */
    private fun foundOnThePage(`when`: String) {
        val hints = suggestionsApi.getCandidates(site, "o1", UniverseFacets.CHARACTERS, null, null).body!!
        val given =
            pages
                .getPage("https://$site.example/work/o1")
                .body!!
                .item!!
                .facets
                .single { it.facet == UniverseFacets.CHARACTERS }
                .propertyValues
                .filter { it.offered != true }
                .map { it.key }
        // A character the work is given already is a chip of its own, not among the hints
        assertEquals(CHARACTERS, (hints.map { it.key } + given).toSet().intersect(CHARACTERS), "$`when`: hints $hints")
        val pilot = hints.find { it.key == "fake:C1" }
        if (pilot != null)
            assertTrue(
                (listOf(pilot.name) + pilot.names.orEmpty()).any { "pilot" in it.lowercase() },
                "$`when`: the pilot by its names $pilot",
            )
    }
}
