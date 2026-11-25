package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * A site's tag not described yet: asked of the browser — the one queue of the pages the application wants of the site,
 * every tag of it in it — and described by what the site answers. Of a site of its own ([SITE]): a capture hands the
 * browser the oldest few requests of its site, and those the other classes' tags left on a shared one would be older.
 */
class SiteTagDescriptionsTest : SiteTestBase() {
    companion object {
        const val SITE = "descsite"
    }

    override val site = SITE

    private fun describe(tag: String) = "https://$SITE.example/tags/$tag"

    @Test
    fun `a site's tag not described yet is asked of the browser once, and what the site answers describes it`() {
        capture("td1|Wreck one|wrecks,hulks|A wreck adrift|")
        val type = stores.type("books")!!
        fun tagOf(tag: String) = TagRepository.key(type.tags.bindings().getValue(SiteTagRef(site, tag)))
        val wrecks = tagOf("wrecks")
        val hulks = tagOf("hulks")
        refresh(stores.source(site)!!)
        // Asked when the tag is looked at: the request written, not waited for
        assertEquals(listOf(site), tagsApi.getTag(wrecks).body!!.awaitedDescriptions)
        assertEquals(listOf(site), tagsApi.getTag(hulks).body!!.awaitedDescriptions)
        // Handed to the browser with the answer of the next page of the site, the oldest first
        val own = listOf(describe("wrecks"), describe("hulks"))
        val asked = capture("td2|Wreck two|wrecks|Another wreck|").body!!.wanted!!.filter { it.url in own }
        assertEquals(own, asked.map { it.url })
        assertEquals(mapOf("tag" to "wrecks"), asked.first().form)
        // The site's answers sent back: the one it describes described; neither asked again, the one
        // the site said nothing of either
        captures.capturePage(CaptureRequest(describe("wrecks"), "Ships broken apart"))
        captures.capturePage(CaptureRequest(describe("hulks"), ""))
        val described = tagsApi.getTag(wrecks).body!!
        assertEquals(
            listOf("Ships broken apart"),
            described.descriptions.filter { it.origin == site }.map { it.text },
        )
        assertEquals(emptyList(), described.awaitedDescriptions)
        assertEquals(emptyList(), tagsApi.getTag(hulks).body!!.awaitedDescriptions)
        assertEquals(
            emptyList(),
            capture("td3|Wreck three|wrecks,hulks|A third wreck|").body!!.wanted.orEmpty().filter { it.url in own },
        )
    }
}
