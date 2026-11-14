package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.UniverseName
import io.github.vlsergey.recommend4me.universe.SiteFandomRef
import io.github.vlsergey.recommend4me.universe.UnusedFandoms
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * A site's fandom no work of its site has any more goes with its universe when the two are one to one; one of a
 * universe with another site's fandom stays, and so does one of a universe the user said anything of.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/unused-fandoms"])
class UnusedFandomsTest : SiteTestBase() {

    @Autowired lateinit var unused: UnusedFandoms

    @Test
    fun `a fandom no work has goes with its universe of it alone, never one of others or of the user's word`() {
        capture("f1|Lorem|magic|Lorem||x1|alone:Lorem Ipsum")
        capture("f2|Ipsum|magic|Ipsum||x2|shared:Dolor")
        capture("f3|Dolor|magic|Dolor||x3|shared-too:dolor")
        capture("f4|Sit|magic|Sit||x4|named:Sit Amet")
        val fandoms = stores.type("books")!!.universes.fandoms
        fun universeOf(key: String) = fandoms.sites.fandomOf(SiteFandomRef("site", key))
        val alone = universeOf("alone")!!
        val shared = universeOf("shared")!!
        val named = universeOf("named")!!
        assertEquals(shared, universeOf("shared-too"))
        universes.nameUniverse(UniverseName(named.toString(), "Sit"))
        // The works give their fandoms up for others
        capture("f1|Lorem|magic|Lorem||x1|other:Consectetur")
        capture("f2|Ipsum|magic|Ipsum||x2|other:Consectetur")
        capture("f4|Sit|magic|Sit||x4|other:Consectetur")

        assertEquals(1, unused.letGo())
        assertNull(universeOf("alone"))
        assertTrue(universes.listUniverses().body!!.none { it.value == alone.toString() })
        // Of a universe with another site's fandom, and of one the user named: they stay
        assertEquals(shared, universeOf("shared"))
        assertEquals(named, universeOf("named"))
        assertNotNull(universeOf("other"))
        assertEquals(0, unused.letGo())
    }
}
