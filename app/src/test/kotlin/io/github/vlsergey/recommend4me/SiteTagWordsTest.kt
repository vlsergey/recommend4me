package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.SitePlugins.SiteSource
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a site says of its tags between them, of a tag's own page, done in the queue: a synonym of a tag is of its
 * normalized tag — the synonym's own of it alone merged into that one —, a tag right above or under another has its
 * normalized tag above or under the other's; what is told before a work has the tag done as one has it; a page read
 * again undoing nothing the user said.
 */
class SiteTagWordsTest : SiteTestBase() {

    private fun tagPage(key: String, synonyms: List<String>, parents: List<String>, children: List<String>) {
        captures.capturePage(
            CaptureRequest(
                "${SiteSource.WORDS}tag/$key",
                listOf(synonyms, parents, children).joinToString("|") { it.joinToString(",") },
            )
        )
    }

    private fun parentsOf(tag: String) = tagsApi.getTag(tag).body!!.parents.map { it.key }.toSet()

    @Test
    fun `a site's synonyms of a tag are of its tag, a tag right above or under another has its tag so`() {
        capture("stw1|Rain|stw-angst|Rain falls|")
        capture("stw2|Snow|stw-angsty|Snow falls|")
        capture("stw3|Hail|stw-emotions|Hail falls|")
        capture("stw4|Mist|stw-hc|Mist falls|")
        val angst = tag("stw-angst")
        val angsty = tag("stw-angsty")
        assertNotEquals(angst, angsty)

        // The tag's page: done in the queue, the synonym's tag of it alone merged into the tag's
        tagPage("stw-angst", listOf("stw-angsty", "stw-late"), listOf("stw-emotions"), listOf("stw-hc"))
        background.run()
        assertEquals(angst, tag("stw-angsty"))
        assertEquals(404, tagsApi.getTag(angsty).statusCode.value())
        assertEquals(setOf(tag("stw-emotions")), parentsOf(angst))
        assertEquals(setOf(angst), parentsOf(tag("stw-hc")))
        // A synonym of no work yet: bound with its tag
        assertEquals(angst, tag("stw-late"))

        // Told before a work had it: under the tag once one has it
        tagPage("stw-deep", emptyList(), listOf("stw-angst"), emptyList())
        capture("stw5|Dew|stw-deep|Dew falls|")
        background.run()
        assertEquals(setOf(angst), parentsOf(tag("stw-deep")))

        // The user takes the synonym out and the tag from under the other: the page read again leaves them so
        tagsApi.bindSiteTag(TagBindingRequest("site", "stw-angsty"))
        val out = tag("stw-angsty")
        assertNotEquals(angst, out)
        tagsApi.relateTags(TagRelationRequest(tag("stw-emotions"), angst, TagRelation.NONE))
        tagPage("stw-angst", listOf("stw-angsty", "stw-late"), listOf("stw-emotions"), listOf("stw-hc"))
        background.run()
        assertEquals(out, tag("stw-angsty"))
        assertTrue(parentsOf(angst).isEmpty())
    }
}
