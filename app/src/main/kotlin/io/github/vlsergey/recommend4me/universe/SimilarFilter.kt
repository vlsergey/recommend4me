package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.UniverseRelation

/**
 * Which of the universes alike one are shown, by what the user said each is to it: in its [hierarchy] — under or above
 * it, directly or through others — and said [different]; of each, only those that are (true), only those that are not
 * (false), or either (null).
 */
data class SimilarFilter(val hierarchy: Boolean? = null, val different: Boolean? = null) {
    /** Whether a universe the user said is [relation] to this one is shown. */
    fun lets(relation: UniverseRelation): Boolean =
        (hierarchy == null || hierarchy == relation in HIERARCHY) &&
            (different == null || different == (relation == UniverseRelation.DIFFERENT))

    private companion object {
        /** The words of a universe in the hierarchy of another. */
        val HIERARCHY =
            setOf(
                UniverseRelation.CHILD,
                UniverseRelation.PARENT,
                UniverseRelation.DESCENDANT,
                UniverseRelation.ANCESTOR,
            )
    }
}
