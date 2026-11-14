package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.SiteFandomBinding
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
 * A site's fandom no work of its site has any more goes with its universe when the two are one to one, alone when the
 * universe has a fandom of the same site of the same name that works have; one of a universe with another site's fandom
 * stays, and so does one of a universe the user said anything of.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/unused-fandoms"])
class UnusedFandomsTest : SiteTestBase() {

    @Autowired lateinit var unused: UnusedFandoms

    @Test
    fun `a fandom no work has goes with its universe of it alone, never one of others or of the user's word`() {
        capture("f1|Lorem|magic|Lorem||x1|alone:Lorem Ipsum")
        capture("f2|Ipsum|magic|Ipsum||x2|shared:Dolor")
        capture("f3|Dolor|magic|Dolor||x3|shared-too:Adipiscing")
        capture("f4|Sit|magic|Sit||x4|named:Sit Amet")
        capture("f5|Amet|magic|Amet||x5|twin:Elit")
        capture("f6|Elit|magic|Elit||x6|pair:Sed Do")
        capture("f6|Elit|magic|Elit||x6|section/pair:Sed Do")
        val fandoms = stores.type("books")!!.universes.fandoms
        fun universeOf(key: String) = fandoms.sites.fandomOf(SiteFandomRef("site", key))
        val alone = universeOf("alone")!!
        val shared = universeOf("shared")!!
        val named = universeOf("named")!!
        // Bound by the user to a universe of a fandom of another name
        universes.bindSiteFandom(SiteFandomBinding("site", "shared-too", shared.toString()))
        assertEquals(shared, universeOf("shared-too"))
        universes.nameUniverse(UniverseName(named.toString(), "Sit"))
        // The works give their fandoms up for others
        capture("f1|Lorem|magic|Lorem||x1|other:Consectetur")
        capture("f2|Ipsum|magic|Ipsum||x2|other:Consectetur")
        capture("f4|Sit|magic|Sit||x4|other:Consectetur")
        capture("f6|Elit|magic|Elit||x6|other:Consectetur")
        // A work's fandom given another key of the same name: of the same universe, the old key's twin
        capture("f5|Amet|magic|Amet||x5|section/twin:Elit")
        val twin = universeOf("twin")!!
        val pair = universeOf("pair")!!
        assertEquals(pair, universeOf("section/pair"))
        assertEquals(twin, universeOf("section/twin"))

        assertEquals(4, unused.letGo())
        assertNull(universeOf("alone"))
        // Two keys of one name of no works, the universe's only: one fandom, gone with it
        assertNull(universeOf("pair"))
        assertNull(universeOf("section/pair"))
        assertTrue(universes.listUniverses().body!!.none { it.value == pair.toString() })
        // The twin of no works gone alone, the universe staying with the key the work has
        assertNull(universeOf("twin"))
        assertEquals(twin, universeOf("section/twin"))
        assertTrue(universes.listUniverses().body!!.none { it.value == alone.toString() })
        // Of a universe with a fandom of another name, and of one the user named: they stay
        assertEquals(shared, universeOf("shared"))
        assertEquals(named, universeOf("named"))
        assertNotNull(universeOf("other"))
        assertEquals(0, unused.letGo())
    }
}
