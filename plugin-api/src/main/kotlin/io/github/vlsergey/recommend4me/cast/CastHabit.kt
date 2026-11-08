package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.source.FacetValue

/**
 * HOW A SITE'S AUTHORS USUALLY WRITE ITS LINE OF CHARACTERS AND PAIRINGS
 * ([Source.castHabit][io.github.vlsergey.recommend4me.source.Source.castHabit]): what a work's line says ([SaidCast])
 * of the [entries][cast] the source keeps of it — the values of its facet of the line
 * ([UniverseFacets.PAIRINGS_FACET][io.github.vlsergey.recommend4me.universe.UniverseFacets.PAIRINGS_FACET]), keys and
 * names as written.
 */
fun interface CastHabit {
    fun cast(entries: List<FacetValue>): SaidCast

    companion object {
        /**
         * The habit of the fan sites, ficbook's first: the entries apart by commas — kept apart already, a value each —
         * the names of a pairing by "/" ("Гарри Поттер/Драко Малфой" a pairing, "Гермиона Грейнджер" a name alone).
         */
        val SLASH = CastHabit { entries -> SaidCast.of(entries.map { it.name ?: it.key }, '/') }
    }
}
