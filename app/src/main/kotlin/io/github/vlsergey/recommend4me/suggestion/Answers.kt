package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE USER'S ANSWERS ON THE FACETS OF THE WORKS AN OPERATION READS, as the dictionaries of the facets read them: every
 * answer by its work ([byItem]), and the facets said whole ([complete]). Of a pass, those it read already
 * ([FacetData.WorksRead.answers]) — a dictionary of every facet read them of the file again, some of them twice and
 * three times a facet.
 */
internal class Answers(val byItem: Map<String, List<FacetCorrection>>, val complete: Map<String, Set<String>>) {
    /** Every answer, one work after another: made once, the dictionaries of several facets reading it. */
    val all: List<FacetCorrection> by lazy { byItem.values.flatten() }

    companion object {
        /** Of the works [ids] — of every work when null — read now. */
        fun of(store: SourceStore, ids: Collection<String>?): Answers =
            Answers(
                if (ids == null) store.corrections.allFacets().groupBy { it.itemId }
                else store.corrections.facetsOf(ids),
                if (ids == null) store.corrections.allComplete() else store.corrections.completeOf(ids),
            )
    }
}
