package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.SitePlugins.SiteSource
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.SiteFandomBinding
import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import io.github.vlsergey.recommend4me.api.model.UniverseRelationRequest
import io.github.vlsergey.recommend4me.universe.FandomDictionary
import io.github.vlsergey.recommend4me.universe.SiteFandomRef
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a site says of its fandoms between them, of a fandom's own page: a synonym of a fandom is of its universe — the
 * synonym's universe of it alone merged into the fandom's —, a fandom under another has its universe under the other's;
 * what is told before a work has the fandom done as one has it; a page read again undoing nothing the user said.
 */
class SiteFandomWordsTest : SiteTestBase() {

    private val fandoms
        get() = stores.type("books")!!.universes.fandoms

    private fun universeOf(key: String) = fandoms.sites.fandomOf(SiteFandomRef("site", key))!!

    private fun parentsOf(universe: Long) = FandomDictionary.load(fandoms).parentsOf(universe)

    private fun fandomPage(key: String, synonyms: List<String>, parents: List<String>) {
        captures.capturePage(
            CaptureRequest(
                "${SiteSource.WORDS}fandom/$key",
                "${synonyms.joinToString(",")}|${parents.joinToString(",")}|",
            )
        )
    }

    @Test
    fun `a fandom's synonyms are of its universe, a fandom under another has its universe under the other's`() {
        capture("sfw1|Lotus|magic|Lotus||sfw|sfw-mdzs:Modao Zushi")
        capture("sfw2|Flute|magic|Flute||sfw|sfw-syn:Mo Dao Zu Shi")
        capture("sfw3|Pier|magic|Pier||sfw|sfw-all:Modao Zushi & Related")
        capture("sfw4|Bunny|magic|Bunny||sfw|sfw-cartoon:Modao Zushi (Cartoon)")
        val mdzs = universeOf("sfw-mdzs")
        val synonym = universeOf("sfw-syn")
        val all = universeOf("sfw-all")
        assertNotEquals(mdzs, synonym)

        // The fandom's page: the synonym's universe of it alone merged into the fandom's; the fandom under the metatag
        fandomPage("sfw-mdzs", listOf("sfw-syn", "sfw-late"), listOf("sfw-all"))
        assertEquals(mdzs, universeOf("sfw-syn"))
        assertFalse(fandoms.exists(synonym))
        assertEquals(setOf(all), parentsOf(mdzs))
        fandomPage("sfw-cartoon", emptyList(), listOf("sfw-all"))
        assertEquals(setOf(all), parentsOf(universeOf("sfw-cartoon")))

        // Told before a work had them: a synonym of the fandom's universe, a fandom under the metatag's
        fandomPage("sfw-untamed", emptyList(), listOf("sfw-all"))
        capture("sfw5|Pond|magic|Pond||sfw|sfw-late:MDZS")
        assertEquals(mdzs, universeOf("sfw-late"))
        capture("sfw6|Cloud|magic|Cloud||sfw|sfw-untamed:The Untamed")
        assertEquals(setOf(all), parentsOf(universeOf("sfw-untamed")))

        // The user takes the synonym out: the page read again leaves it out, and the fandom where the user put it
        universes.bindSiteFandom(SiteFandomBinding("site", "sfw-syn"))
        val out = universeOf("sfw-syn")
        assertNotEquals(mdzs, out)
        universes.relateUniverses(UniverseRelationRequest(all.toString(), mdzs.toString(), UniverseRelation.NONE))
        fandomPage("sfw-mdzs", listOf("sfw-syn", "sfw-late"), listOf("sfw-all"))
        assertEquals(out, universeOf("sfw-syn"))
        assertTrue(parentsOf(mdzs).isEmpty())
    }
}
