package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.FacetDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CappedChancesTest {

    private fun chance(key: String, p: Double, had: Boolean = false) = Chance(1, "tags", key, had, p, likely = p >= 0.3)

    /**
     * Of a facet of a hard limit the likeliest values the work has not stay likely, the others told not; the values it
     * has and those not likely untouched.
     */
    @Test
    fun `no more values likely than the facet's limit, the likeliest kept`() {
        val chances =
            FewChances(
                arrayOf(
                    chance("a", 0.9),
                    chance("b", 0.5),
                    chance("c", 0.7),
                    chance("d", 0.4),
                    chance("own", 0.95, had = true),
                    chance("low", 0.1),
                )
            )
        val capped = ShownChances.capped(FacetDef("tags", infer = true, most = 2), chances)
        assertEquals(setOf("a", "c", "own"), capped.values.filter { it.likely }.map { it.key }.toSet())
        assertEquals(chances.values.map { it.chance }, capped.values.map { it.chance })
        assertSame(chances, ShownChances.capped(FacetDef("tags", infer = true), chances))
        assertSame(chances, ShownChances.capped(FacetDef("tags", infer = true, most = 4), chances))
    }
}
