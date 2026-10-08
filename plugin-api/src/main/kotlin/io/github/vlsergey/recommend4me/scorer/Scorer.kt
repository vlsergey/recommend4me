package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix

/**
 * The rated rows a scorer learns from: the features of every rating ([x], a row each), its grade,
 * the work it is of and the item it is of. Two ratings of one work never fall into different
 * folds and are never put in order by their grades. Two items of one work — one book on two
 * sites, linked by the user — are the same work seen twice: compared, they are EQUAL, whatever
 * their grades. Two versions of one item are not compared at all: an update may make a game better.
 */
class RankingTask(val x: Matrix, val grades: IntArray, val works: LongArray, val items: LongArray) {
    init {
        require(grades.size == x.rows && works.size == x.rows && items.size == x.rows) {
            "${x.rows} rows, ${grades.size} grades, ${works.size} works, ${items.size} items"
        }
    }
}

/**
 * A way of turning the features of a work into a score: the higher, the better the user will like
 * it. Only the ORDER of the scores matters — the application places the grades on 0..10 from the
 * scores of works each fold had not seen.
 *
 * The scorer has one parameter (a penalty, a number of neighbours); the application measures
 * every one of [candidates] by cross-validation over works and takes the first — the most
 * cautious — whose quality is within one standard error of the best.
 */
interface Scorer {
    /** Stable: kept with the trained model. */
    val id: String

    /** The name shown to the user. */
    val title: String

    /** The values of the parameter, the most cautious first. */
    val candidates: List<Double>

    /** The value used when there are too few works to cross-validate. */
    val defaultParameter: Double

    /**
     * Learns from the rows [rows] of [task]. Called from many threads at once by the
     * cross-validation: it should not spread one fit over the cores by itself.
     */
    fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer

    /** A model kept by [FittedScorer.pack]. */
    fun unpack(bytes: ByteArray): FittedScorer
}

/** A trained scorer. */
interface FittedScorer {
    /** The score of each of the first [rows] rows of [x]. */
    fun scores(x: Matrix, rows: Int = x.rows): FloatArray

    fun pack(): ByteArray
}
