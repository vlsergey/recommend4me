package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListPredictions
import kotlin.math.abs
import kotlin.math.ln

/**
 * WHAT A PAIR IS CHOSEN AND ORDERED BY ON A PAGE, worked out when the search fills the table and kept with the pair
 * (owner, 2026-11-20: "do it exactly when the table is filled"; [DuplicateChoice]): its chance of one work ([same]) and
 * of one series ([series]) — none of a series without a model of one —, the user's word on it ([answer]; [derived]: of
 * one series by the series, not by a word of the user's, [Said.derived]), whether its two works have every normalized
 * author alike ([sameAuthors]) and the same title ([sameTitle], [WholeMatch]), and what every other sort orders it by
 * ([ranks], by [RANKED]; none of what is not known).
 */
class PairScore(
    val same: Double,
    val series: Double?,
    val answer: DuplicateAnswer?,
    val derived: Boolean,
    val sameAuthors: Boolean,
    val sameTitle: Boolean,
    val ranks: Array<Double?>,
) {
    companion object {
        /** The sorts of a key of their own besides the chances, in the order of [ranks]. */
        val RANKED =
            listOf(
                DuplicateSort.SIZE,
                DuplicateSort.SERIES_SIZE,
                DuplicateSort.UNCERTAIN,
                DuplicateSort.UNCERTAIN_SERIES,
                DuplicateSort.FORMULA,
                DuplicateSort.INTEREST,
                DuplicateSort.GRADE,
            )

        /**
         * The scores of the [pairs] of a search by their chances as it made them, the user's words [said], what their
         * works have alike ([match]: of the works as the search read them), and what every sort says of them as things
         * stand: the sizes the search compared, where the user's answers change, the works' predictions as the [index]
         * of the list keeps them and the user's marks of interest.
         */
        internal fun of(
            type: TypeStore,
            index: ListIndex,
            pairs: Collection<FoundCandidate>,
            said: Said,
            match: WholeMatch,
        ): (FoundCandidate) -> PairScore {
            val doubt = PairDoubt.of(pairs, said)
            val worth = PairWorth.of(type, index)
            return { f ->
                val c = f.candidate
                val chance = f.same
                val ofSeries = f.series
                val size = sizeOf(c)
                // The word on the pair looked up once: the derived are of the pairs with a word
                val pair = c.pair
                val word = said[pair]
                val sought = worth.sought(c)
                PairScore(
                    chance,
                    ofSeries,
                    word,
                    word != null && pair in said.derived,
                    match.authors(f),
                    match.title(f),
                    arrayOf(
                        size * chance,
                        ofSeries?.let { size * it },
                        doubt.ofSame(chance),
                        ofSeries?.let(doubt::ofSeries),
                        sought.formula * chance,
                        sought.interest * chance,
                        sought.grade * chance,
                    ),
                )
            }
        }

        /** The mean of the two works' sizes in words, of those known: of a work of none, the other's alone. */
        private fun sizeOf(c: DuplicateCandidate): Double {
            val known = listOfNotNull(c.words, c.otherWords)
            return if (known.isEmpty()) 0.0 else known.average()
        }
    }
}

/**
 * WHERE THE USER'S ANSWERS CHANGE, on the scale a logistic model adds its chances up on: between the mean of the pairs
 * said to be of a kind and of those said not — of one work, those said one work and those said different; of one
 * series, those said one series and those given any other word —; nought with neither kind. A pair's doubt is how near
 * its chance is to it: one at it, less the farther.
 */
internal class PairDoubt(private val same: Double, private val series: Double) {
    fun ofSame(chance: Double) = 1 / (1 + abs(logit(chance) - same))

    fun ofSeries(chance: Double) = 1 / (1 + abs(logit(chance) - series))

    companion object {
        /** A chance of nought or one has no place on the scale of [logit]. */
        private const val LEAST = 1e-12

        fun logit(chance: Double): Double = chance.coerceIn(LEAST, 1 - LEAST).let { ln(it / (1 - it)) }

        /** Where the answers [said] on the [pairs] change, by their chances of one work and of one series. */
        internal fun of(pairs: Collection<FoundCandidate>, said: Said): PairDoubt {
            val answered = pairs.filter { it.candidate.pair in said }
            fun between(yes: List<Double>, no: List<Double>) =
                if (yes.isEmpty() || no.isEmpty()) 0.0 else (yes.average() + no.average()) / 2
            fun word(f: FoundCandidate) = said[f.candidate.pair]
            val ofSame =
                between(
                    answered.filter { word(it) == DuplicateAnswer.SAME }.map { logit(it.same) },
                    answered.filter { word(it) == DuplicateAnswer.DIFFERENT }.map { logit(it.same) },
                )
            val ofSeries =
                between(
                    answered.filter { word(it) == DuplicateAnswer.SERIES }.mapNotNull { it.series?.let(::logit) },
                    answered.filter { word(it) != DuplicateAnswer.SERIES }.mapNotNull { it.series?.let(::logit) },
                )
            return PairDoubt(ofSame, ofSeries)
        }
    }
}

/**
 * WHAT A SORT SAYS OF THE WORK A PAIR WAS FOUND FOR — of both, the larger; of neither (an answered pair not found by
 * the search itself), the larger too —: of the predicted grade (not below nought) and the chance of interest, the
 * user's mark over the model's; nought of a work not predicted. Of every source's predictions and marks as the search
 * reads them.
 */
internal class PairWorth(
    private val predicted: Map<String, Map<String, Predicted>>,
    private val marks: Map<String, Map<String, Boolean>>,
) {
    /** What the sorts of the formula, of interest and of the grade say of the pair [c]: each work looked up once. */
    fun sought(c: DuplicateCandidate): Sought {
        val first = of(c.first)
        val second = of(c.second)
        return when (c.foundFor) {
            DuplicateCandidate.FIRST -> first
            DuplicateCandidate.SECOND -> second
            else ->
                Sought(
                    maxOf(first.formula, second.formula),
                    maxOf(first.interest, second.interest),
                    maxOf(first.grade, second.grade),
                )
        }
    }

    /** What a sort says of a work: of the formula, the grade times the chance of interest. */
    class Sought(val formula: Double, val interest: Double, val grade: Double)

    private fun of(key: ItemKey): Sought {
        val p = predicted[key.source]?.get(key.id) ?: return NONE
        val grade = p.score.coerceAtLeast(0.0)
        val interest = Interest.chance(marks[key.source]?.get(key.id), p.interest) ?: 1.0
        return Sought(grade * interest, interest, grade)
    }

    companion object {
        /** What the sorts say of a work not predicted. */
        private val NONE = Sought(0.0, 0.0, 0.0)

        /**
         * Of every source of the [type] its predictions as the index of the list keeps them ([index]) and the user's
         * marks of interest: a search's reading.
         */
        fun of(type: TypeStore, index: ListIndex) =
            PairWorth(
                type.sources.associate { s ->
                    s.id to index.searching { ListPredictions.ofEvery(it, s.id) }
                },
                type.sources.associate { s -> s.id to s.interest.all().mapValues { it.value.interesting } },
            )
    }
}
