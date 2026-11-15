package io.github.vlsergey.recommend4me.author

import kotlin.test.Test
import kotlin.test.assertEquals

class OfferFilterTest {
    private val sites = mapOf(1L to setOf("ficbook"), 2L to setOf("ao3", "ficbook"), 3L to emptySet())

    private fun shown(filter: OfferFilter) =
        listOf(1L to 2L, 1L to 3L, 2L to 3L).filter { filter.lets(it) { id -> sites.getValue(id) } }

    @Test
    fun `a site asked for is of either author, a site refused of neither`() {
        assertEquals(listOf(1L to 2L, 1L to 3L, 2L to 3L), shown(OfferFilter()))
        assertEquals(listOf(1L to 2L, 2L to 3L), shown(OfferFilter(onSite = setOf("ao3"))))
        assertEquals(listOf(1L to 2L, 2L to 3L), shown(OfferFilter(onSite = setOf("ao3", "ficbook"))))
        assertEquals(listOf(1L to 3L), shown(OfferFilter(offSite = setOf("ao3"))))
        assertEquals(listOf(1L to 3L), shown(OfferFilter(onSite = setOf("ficbook"), offSite = setOf("ao3"))))
        assertEquals(listOf(1L to 3L), shown(OfferFilter(author = 3L, offSite = setOf("ao3"))))
    }
}
