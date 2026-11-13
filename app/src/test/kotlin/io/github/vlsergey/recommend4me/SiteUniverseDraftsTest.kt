package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.MarkAnswer
import io.github.vlsergey.recommend4me.api.model.MarkVerdict
import io.github.vlsergey.recommend4me.api.model.MarkingOrder
import io.github.vlsergey.recommend4me.api.model.UniverseEntryBinding
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * Characters and a universe drafted from the site's tags by the dictionary — the entries of every universe the
 * dictionary has, made and taken away — and the user's word over them; the works of a pairing of the dictionary offered
 * to mark.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/site-universe-drafts"])
class SiteUniverseDraftsTest : SiteTestBase() {

    /** The universe of the entry U2 of the catalogue, as the test running made it: what its steps read. */
    private lateinit var u2: String

    @Test
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
