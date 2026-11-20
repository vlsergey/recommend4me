package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * WHAT A PAIR IS CHOSEN AND ORDERED BY ON A PAGE, kept with it ([DuplicateChoice]): its chance of one work ([same]) and
 * of one series ([series]) as the models last scored it — none of a series without a model of one —, the user's word on
 * it ([answer]; [derived]: of one series by the series, not by a word of the user's, [Said.derived]), and whether its
 * two works have every normalized author alike ([sameAuthors]) and the same title ([sameTitle], [WholeMatch]).
 */
class PairScore(
    val same: Double,
    val series: Double?,
    val answer: DuplicateAnswer?,
    val derived: Boolean,
    val sameAuthors: Boolean,
    val sameTitle: Boolean,
) {
    companion object {
        /**
         * The scores of the [pairs] of a search by the models of one work [same] and of one series [series], the user's
         * words [said], and what their works have alike, read of every work of their sources ([WholeMatch]).
         */
        internal fun of(
            type: TypeStore,
            pairs: Collection<DuplicateCandidate>,
            said: Said,
            same: FittedPairModel,
            series: FittedPairModel?,
        ): (DuplicateCandidate) -> PairScore {
            val ofSources = pairs.asSequence().flatMap { sequenceOf(it.first, it.second) }.distinctBy { it.source }
            val match = WholeMatch(type, ofSources.toList(), authors = true, titles = true)
            return { c ->
                PairScore(
                    same.chance(c.layers),
                    series?.chance(c.layers),
                    said[c.pair],
                    c.pair in said.derived,
                    match.authors(c.pair),
                    match.title(c.pair),
                )
            }
        }
    }
}
