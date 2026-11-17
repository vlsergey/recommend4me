package io.github.vlsergey.recommend4me.suggestion

import java.util.IdentityHashMap

/**
 * THE WEIGHTS OF THE PAIRS OF EVERY VALUE OVER EVERY WORK OF THE PORTIONS, by the fold of [SuggestionPairs] each work
 * is of, summed a portion at a time ([add]): the share of a value among all the works, and among those of the other
 * folds of a work held out with its fold — of [m] values, whatever the number of works.
 *
 * A pair merely lacked weighs the same over every value a work may have: such weights are summed by the array of the
 * values the works of a portion may have ([SuggestionPairs.may], one for every work of the same) and spread over its
 * values once a portion, not a pair at a time — of a universe of thousands of characters, thousands of pairs a work.
 */
internal class FoldSums(private val m: Int) {
    private val one = List(SuggestionPairs.FOLDS) { DoubleArray(m) }
    private val all = List(SuggestionPairs.FOLDS) { DoubleArray(m) }

    /** Of every fold, the weight merely lacked of every value, of the works that may have any. */
    private val everyLacked = DoubleArray(SuggestionPairs.FOLDS)

    /** The pairs of every work of a portion summed. */
    fun add(pairs: SuggestionPairs) {
        val lacked = IdentityHashMap<IntArray, DoubleArray>()
        for (i in 0 until pairs.n) {
            val f = pairs.foldOf[i]
            val lack = pairs.lack[i]
            if (lack > 0f) {
                val may = pairs.may[i]
                if (may == null) everyLacked[f] += lack.toDouble()
                else lacked.getOrPut(may) { DoubleArray(SuggestionPairs.FOLDS) }[f] += lack.toDouble()
            }
            // The work's own pairs in place of the lacking weight of their values
            pairs.own[i].forEachIndexed { k, v ->
                val w = pairs.ownWeight[i][k]
                all[f][v] += (w - pairs.lacking(i, v)).toDouble()
                one[f][v] += (w * pairs.ownLabel[i][k]).toDouble()
            }
        }
        lacked.forEach { (may, byFold) ->
            byFold.forEachIndexed { f, weight -> if (weight != 0.0) may.forEach { all[f][it] += weight } }
        }
    }

    /** The sums of the works of every fold. */
    fun folds(): List<Labels.Sums> =
        List(SuggestionPairs.FOLDS) { f ->
            Labels.Sums(one[f].copyOf(), DoubleArray(m) { all[f][it] + everyLacked[f] })
        }

    companion object {
        /** The sums of every work of [folds]. */
        fun total(folds: List<Labels.Sums>): Labels.Sums {
            val m = folds.first().one.size
            return Labels.Sums(
                DoubleArray(m) { v -> folds.sumOf { it.one[v] } },
                DoubleArray(m) { v -> folds.sumOf { it.all[v] } },
            )
        }
    }
}
