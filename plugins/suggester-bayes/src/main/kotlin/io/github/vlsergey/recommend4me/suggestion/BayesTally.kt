package io.github.vlsergey.recommend4me.suggestion

/**
 * THE COUNTS AS THEY ARE SUMMED, a work at a time as the portions are walked ([add]), or a tally of other works at once
 * ([addAll]): of [m] values and [contextCount] values of the other facets, what [BayesCounts] is made of ([counts]) —
 * as large as the values and their contexts, whatever the number of works.
 */
internal class BayesTally(private val m: Int, contextCount: Int) {
    private var works = 0
    private var uniformTotal = 0.0
    private val zero = DoubleArray(m)
    private val one = DoubleArray(m)
    private val contextUniform = DoubleArray(contextCount)
    private val contextWorks = IntArray(contextCount)
    private val contextZero = HashMap<Int, HashMap<Int, Double>>()
    private val contextOne = HashMap<Int, HashMap<Int, Double>>()

    /** The work [w] counted. */
    fun add(w: BayesWork) {
        works++
        uniformTotal += w.uniform
        w.values.forEachIndexed { k, v ->
            zero[v] += w.zeros[k]
            one[v] += w.ones[k]
        }
        w.contexts.forEach { c ->
            contextWorks[c]++
            contextUniform[c] += w.uniform.toDouble()
            if (w.values.isEmpty()) return@forEach
            val z = contextZero.getOrPut(c) { HashMap() }
            val o = contextOne.getOrPut(c) { HashMap() }
            w.values.forEachIndexed { k, v ->
                if (w.zeros[k] != 0f) z.merge(v, w.zeros[k].toDouble(), Double::plus)
                if (w.ones[k] != 0f) o.merge(v, w.ones[k].toDouble(), Double::plus)
            }
        }
    }

    /** Every work [other] counted, counted here too. */
    fun addAll(other: BayesTally) {
        works += other.works
        uniformTotal += other.uniformTotal
        for (v in 0 until m) {
            zero[v] += other.zero[v]
            one[v] += other.one[v]
        }
        other.contextUniform.forEachIndexed { c, u -> contextUniform[c] += u }
        other.contextWorks.forEachIndexed { c, n -> contextWorks[c] += n }
        listOf(contextZero to other.contextZero, contextOne to other.contextOne).forEach { (mine, theirs) ->
            theirs.forEach { (c, values) ->
                val into = mine.getOrPut(c) { HashMap() }
                values.forEach { (v, w) -> into.merge(v, w, Double::plus) }
            }
        }
    }

    /** The counts of every work counted: the tally's last word — its maps are theirs, nothing added after. */
    fun counts(): BayesCounts =
        BayesCounts(
            m,
            works,
            DoubleArray(m) { zero[it] + uniformTotal },
            one.copyOf(),
            contextUniform,
            contextWorks,
            contextZero,
            contextOne,
        )
}

/**
 * THE COUNTS OF EVERY WORK OF THE PORTIONS, OF ONE WALK: of every fold of [SuggestionPairs] the counts of its works
 * ([folds]) — a work held out with its fold is judged by every count but its fold's ([BayesCounts.chances] `less`) —
 * and of every work ([total]). [valued]: whether any work has a value: without, there is nothing to learn from.
 */
internal class BayesFolds(val folds: List<BayesCounts>, val total: BayesCounts, val valued: Boolean) {
    companion object {
        /** One walk of the [portions], [stop] asked before every portion. */
        fun of(portions: TaskPortions, stop: () -> Boolean): BayesFolds {
            val m = portions.values.valueCount
            val contexts = portions.values.contextCount
            val tallies = List(SuggestionPairs.FOLDS) { BayesTally(m, contexts) }
            var valued = false
            portions.forEach { portion ->
                stopIf(stop)
                val pairs = SuggestionPairs(portion)
                BayesWork.of(portion, pairs).forEachIndexed { i, w -> tallies[pairs.foldOf[i]].add(w) }
                valued = valued || portion.assigned.any { it.isNotEmpty() }
            }
            val total = BayesTally(m, contexts)
            tallies.forEach(total::addAll)
            return BayesFolds(tallies.map { it.counts() }, total.counts(), valued)
        }
    }
}
