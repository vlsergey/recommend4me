package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * A value a site gave by its name alone — a dump, under the prefix the source says of such keys — is given the key a
 * page of the site gives a value of that name, on every work of the name, in the background: in its place, once on a
 * work that has the key already; never the key of a name two keys of the facet have.
 */
class NamedValuesTest : TwoSitesTestBase() {

    private val author = Books.AUTHOR.key

    private fun authorsOf(id: String) = stores.source("alpha")!!.items.facets(id)[author]

    @Test
    fun `a value given by its name is given the key a page gives the name, on every work of it`() {
        alpha("nv1|Lorem|hay|Lorem ipsum dolor||")
        alpha("nv2|Ipsum|hay|Sit amet consectetur||")
        alpha("nv3|Dolor|hay|Adipiscing elit||")
        val items = stores.source("alpha")!!.items
        // A dump gave the works their authors by the names: Nova of all three, the third written by Vera and Nova
        items.setFacet("nv1", author, listOf(FacetValue("name:Nova", "Nova")))
        items.setFacet("nv2", author, listOf(FacetValue("name:Nova", "Nova")))
        items.setFacet("nv3", author, listOf(FacetValue("name:Vera", "Vera"), FacetValue("name:Nova", "Nova")))

        // The site's page of the first work gives Nova her key; the other works keyed so in the background
        items.setFacet("nv1", author, listOf(FacetValue("nv-nova", "Nova")))
        assertEquals(listOf("name:Nova"), authorsOf("nv2"))
        background.run()

        assertEquals(listOf("nv-nova"), authorsOf("nv1"))
        assertEquals(listOf("nv-nova"), authorsOf("nv2"))
        assertEquals(listOf("name:Vera", "nv-nova"), authorsOf("nv3"))
    }

    @Test
    fun `a work that has the key already keeps it once`() {
        alpha("nw1|Lorem|hay|Lorem ipsum dolor||")
        val items = stores.source("alpha")!!.items
        items.setFacet("nw1", author, listOf(FacetValue("name:Wren", "Wren"), FacetValue("nw-wren", "Wren")))
        background.run()

        assertEquals(listOf("nw-wren"), authorsOf("nw1"))
    }

    @Test
    fun `a name two keys have stays a name`() {
        alpha("nx1|Lorem|hay|Lorem ipsum dolor||")
        alpha("nx2|Ipsum|hay|Sit amet consectetur||")
        alpha("nx3|Dolor|hay|Adipiscing elit||")
        val items = stores.source("alpha")!!.items
        // Two accounts of the site named Twin, and a work the dump gave by the name
        items.setFacet("nx2", author, listOf(FacetValue("nx-twin-1", "Twin")))
        items.setFacet("nx3", author, listOf(FacetValue("nx-twin-2", "Twin")))
        items.setFacet("nx1", author, listOf(FacetValue("name:Twin", "Twin")))

        // A page of the first account's work read again: its co-author added
        items.setFacet("nx2", author, listOf(FacetValue("nx-twin-1", "Twin"), FacetValue("nx-ally", "Ally")))
        background.run()

        assertEquals(listOf("name:Twin"), authorsOf("nx1"))
    }
}
