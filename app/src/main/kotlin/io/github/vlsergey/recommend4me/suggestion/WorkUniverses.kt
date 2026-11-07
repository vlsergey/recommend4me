package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * THE UNIVERSES AND THE CHARACTERS EVERY WORK HAS, as the user's answers and the model before in the chain give them:
 * what the dictionaries of the characters and the pairings read of a work ([UniverseVocabularies]).
 */
internal object WorkUniverses {
    /**
     * The characters left out of the [facet] of every item ([UniverseFacet.leftOut]): those of its other facet of
     * characters — the user's answers, and the main ones the model gives it. The main characters are worked out before
     * the secondary ones in the chain: [made] has them.
     */
    fun leftOut(answers: Answers, facet: String, made: WorkChances): (String) -> Set<String> {
        if (UniverseFacet.otherCharacters(facet) == null) return { emptySet() }
        val secondary = facet == UniverseFacets.SECONDARY_CHARACTERS
        return { id ->
            val own = answers.byItem[id].orEmpty().filter { it.facet in UniverseFacet.CHARACTER_FACETS }
            // The main characters the model gives the work: of this work alone, as it is asked of
            val given =
                if (secondary) ModelValues.given(made[id]?.get(UniverseFacets.CHARACTERS).orEmpty()) else emptyList()
            fun said(f: String) = own.filter { it.facet == f && it.added }.map { it.key }
            fun has(f: String) =
                ((if (f == UniverseFacets.CHARACTERS) given else emptyList()) + said(f)).toSet() -
                    own.filter { it.facet == f && !it.added }.map { it.key }.toSet()
            UniverseFacet.leftOut(facet, ::has, ::said)
        }
    }

    /**
     * The universes whose characters every work may have: the ones the user confirmed, and the ones the model gives it
     * — more likely than not — unless the user rejected them; none but the confirmed once the user said the work has no
     * other universes. The universes the model gives are [made] before the characters in the chain.
     */
    fun universesOf(answered: Answers, made: WorkChances): Map<String, List<String>> {
        val answers = answered.all.filter { it.facet == UniverseFacets.UNIVERSE }.groupBy { it.itemId }
        val complete = answered.complete
        val given = made
        return (answers.keys + given.keys).associateWith { id ->
            val own = answers[id].orEmpty()
            val confirmed = own.filter { it.added }.map { it.key }
            if (UniverseFacets.UNIVERSE in complete[id].orEmpty()) confirmed
            else
                (confirmed + ModelValues.given(given[id]?.get(UniverseFacets.UNIVERSE).orEmpty()) -
                        own.filter { !it.added }.map { it.key }.toSet())
                    .distinct()
        }
    }

    /** The values of a facet the user linked every item — the items [ids], when given — to: item to values. */
    fun linked(answers: Answers, facet: String): Map<String, List<String>> =
        answers.all.filter { it.facet == facet && it.added }.groupBy({ it.itemId }, { it.key })
}
