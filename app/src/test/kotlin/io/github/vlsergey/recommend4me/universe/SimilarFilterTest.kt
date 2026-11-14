package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import kotlin.test.Test
import kotlin.test.assertEquals

class SimilarFilterTest {

    private fun shown(filter: SimilarFilter) = UniverseRelation.entries.filter(filter::lets).toSet()

    @Test
    fun `the alike are filtered by the hierarchy and by the word of a different one, each either way or not at all`() {
        val hierarchy =
            setOf(
                UniverseRelation.CHILD,
                UniverseRelation.PARENT,
                UniverseRelation.DESCENDANT,
                UniverseRelation.ANCESTOR,
            )
        assertEquals(UniverseRelation.entries.toSet(), shown(SimilarFilter()))
        assertEquals(hierarchy, shown(SimilarFilter(hierarchy = true)))
        assertEquals(UniverseRelation.entries.toSet() - hierarchy, shown(SimilarFilter(hierarchy = false)))
        assertEquals(setOf(UniverseRelation.DIFFERENT), shown(SimilarFilter(different = true)))
        assertEquals(
            setOf(UniverseRelation.MERGED, UniverseRelation.NONE),
            shown(SimilarFilter(hierarchy = false, different = false)),
        )
    }
}
