package io.github.vlsergey.recommend4me.suggestion

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlin.math.ln

/**
 * WHAT A WALK OVER EVERY ITEM OF A FACET TELLS BEFORE THE DESCENT ([Descent]) FROM SCRATCH, a portion at a time: the
 * layout of the features ([design]) — the typical length of every input block's vectors is of every item, not of a
 * portion's; the values [used] — some pair of which weighs anything: their own coordinates start small and random, the
 * rest at nought; the base rate of the pairs — the common bias starts at its logit ([common]); how many [portions], and
 * how many items have any value of the facet ([assigned]).
 */
internal class Survey
private constructor(
    val design: Design,
    val used: BooleanArray,
    val common: Float,
    val portions: Int,
    val assigned: Int,
) {
    /** What the walk sums as it goes: kept of no portion but these numbers. */
    private class Sums(m: Int) {
        val count = HashMap<String, Long>()
        val lengths = HashMap<String, Double>()
        val used = BooleanArray(m)
        var every = false
        var weight = 0.0
        var positive = 0.0
        var portions = 0
        var assigned = 0

        /** The [portion]'s blocks' lengths, its values used, its pairs' weights and labels added. */
        fun add(portion: SuggestionTask) {
            portions++
            portion.inputs.forEach { b ->
                count.merge(b.name, b.rows.size.toLong(), Long::plus)
                lengths.merge(b.name, BlockScale.lengths(b.vectors), Double::plus)
            }
            assigned += portion.assigned.count { it.isNotEmpty() }
            val pairs = SuggestionPairs(portion)
            // The values an item may lack, every array of them once, and its own pairs
            val marked = Collections.newSetFromMap(IdentityHashMap<IntArray, Boolean>())
            for (i in 0 until portion.itemCount) {
                if (pairs.lack[i] > 0f) {
                    val may = pairs.may[i]
                    if (may == null) every = true else if (marked.add(may)) may.forEach { used[it] = true }
                }
                pairs.own[i].forEachIndexed { k, v ->
                    if (pairs.ownWeight[i][k] != 0f) used[v] = true
                    positive += pairs.ownWeight[i][k] * pairs.ownLabel[i][k]
                }
                weight += pairs.rowWeight[i]
            }
        }
    }

    companion object {
        /** A base rate kept off 0 and 1: its logit finite. */
        private const val EDGE = 1e-6

        /** The walk over the [portions]; [stop] asked between them. */
        fun of(portions: TaskPortions, stop: () -> Boolean): Survey {
            val values = portions.values
            val sums = Sums(values.valueCount)
            portions.forEach { portion ->
                if (stop()) throw CancellationException("the fitting is given up")
                sums.add(portion)
            }
            if (sums.every) sums.used.fill(true)
            // The names' rivals: one array for every name of the same universe, gone through once; a name its own
            // value's one positive pair among them
            val marked = Collections.newSetFromMap(IdentityHashMap<IntArray, Boolean>())
            values.hints.forEach { hint ->
                if (marked.add(hint.among)) hint.among.forEach { sums.used[it] = true }
                sums.weight += hint.among.size
                sums.positive += 1
            }
            val blocks =
                values.inputs.mapNotNull { b ->
                    BlockScale.of(b.name, b.dim, sums.count[b.name] ?: 0L, sums.lengths[b.name] ?: 0.0)
                }
            val rate = if (sums.weight > 0) (sums.positive / sums.weight).coerceIn(EDGE, 1 - EDGE) else 1.0 / 2
            return Survey(
                Design.of(values, blocks),
                sums.used,
                ln(rate / (1 - rate)).toFloat(),
                sums.portions,
                sums.assigned,
            )
        }
    }
}
