package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import kotlin.math.abs
import kotlin.math.ln

/**
 * WHAT A PAIR IS CHOSEN AND ORDERED BY ON A PAGE, worked out when the search fills the table and kept with the pair
 * (owner, 2026-11-20: "do it exactly when the table is filled"; [DuplicateChoice]): its chance of one work ([same]) and
 * of one series ([series]) — none of a series without a model of one —, the user's word on it ([answer]; [derived]: of
 * one series by the series, not by a word of the user's, [Said.derived]), whether its two works have every normalized
 * author alike ([sameAuthors]) and the same title ([sameTitle], [WholeMatch]), and what every other sort orders it by
 * ([ranks], by [RANKED]; [NONE] of what is not known: numbers, not a boxed one each, of millions of pairs).
 */
class PairScore(
    val same: Double,
    val series: Double?,
    val answer: DuplicateAnswer?,
    val derived: Boolean,
    val sameAuthors: Boolean,
    val sameTitle: Boolean,
    val ranks: DoubleArray,
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

        /** A rank not known: of a pair of no chance of one series. */
        const val NONE = Double.NaN

        /**
         * The scores of the [pairs] of a search by their chances and the user's words as it found them, what their
         * works have alike ([match]) and what every sort says of them as things stand ([worth]): of the works as the
         * search read them — the sizes it compared, the works' predictions and chances of interest —, and where the
         * user's answers change.
         */
        internal fun of(
            pairs: Collection<FoundCandidate>,
            match: WholeMatch,
            worth: PairWorth,
        ): (FoundCandidate) -> PairScore {
            val doubt = PairDoubt.of(pairs)
            return { f ->
                val c = f.candidate
                val chance = f.same
                val ofSeries = f.series
                val size = sizeOf(c)
                val sought = worth.sought(f)
                PairScore(
                    chance,
                    ofSeries,
                    f.word,
                    f.derived,
                    match.authors(f),
                    match.title(f),
                    doubleArrayOf(
                        size * chance,
                        if (ofSeries == null) NONE else size * ofSeries,
                        doubt.ofSame(chance),
                        if (ofSeries == null) NONE else doubt.ofSeries(ofSeries),
                        sought.formula * chance,
                        sought.interest * chance,
                        sought.grade * chance,
                    ),
                )
            }
        }

        /** The mean of the two works' sizes in words, of those known: of a work of none, the other's alone. */
        private fun sizeOf(c: DuplicateCandidate): Double {
            val a = c.words
            val b = c.otherWords
            return when {
                a != null && b != null -> (a.toDouble() + b) / 2
                else -> (a ?: b)?.toDouble() ?: 0.0
            }
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

        /** Where the user's words on the [pairs] change, by their chances of one work and of one series. */
        internal fun of(pairs: Collection<FoundCandidate>): PairDoubt {
            val answered = pairs.filter { it.word != null }
            fun between(yes: List<Double>, no: List<Double>) =
                if (yes.isEmpty() || no.isEmpty()) 0.0 else (yes.average() + no.average()) / 2
            val ofSame =
                between(
                    answered.filter { it.word == DuplicateAnswer.SAME }.map { logit(it.same) },
                    answered.filter { it.word == DuplicateAnswer.DIFFERENT }.map { logit(it.same) },
                )
            val ofSeries =
                between(
                    answered.filter { it.word == DuplicateAnswer.SERIES }.mapNotNull { it.series?.let(::logit) },
                    answered.filter { it.word != DuplicateAnswer.SERIES }.mapNotNull { it.series?.let(::logit) },
                )
            return PairDoubt(ofSame, ofSeries)
        }
    }
}

/**
 * WHAT A SORT SAYS OF THE WORK A PAIR WAS FOUND FOR — of both, the larger; of neither (an answered pair not found by
 * the search itself), the larger too —: of the predicted grade (not below nought) and the chance of interest, the
 * user's mark over the model's (1 of none); nought of a work not predicted. Of every work's prediction and chance as
 * the index of the list keeps them, read with the works of the search ([SearchSide.predictions]), by their places: a
 * reading of every prediction by its key and of every mark of interest of the databases, of every search, took 5 s of
 * the books (2026-11-22).
 */
internal class PairWorth(private val sides: List<SearchSide>) {
    /** What the sorts of the formula, of interest and of the grade say of the pair [f]: each work looked up once. */
    fun sought(f: FoundCandidate): Sought {
        val first = of(f.site, f.item)
        val second = of(f.otherSite, f.other)
        return when (f.candidate.foundFor) {
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

    /** What the sorts say of the [a]-th site's [i]-th work. */
    private fun of(a: Int, i: Int): Sought {
        val score = sides[a].predictions?.get(i)?.takeUnless { it.isNaN() } ?: return NONE
        val grade = score.coerceAtLeast(0.0)
        val interest = sides[a].interests?.get(i)?.takeUnless { it.isNaN() } ?: 1.0
        return Sought(grade * interest, interest, grade)
    }

    private companion object {
        /** What the sorts say of a work not predicted. */
        val NONE = Sought(0.0, 0.0, 0.0)
    }
}
