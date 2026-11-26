package io.github.vlsergey.recommend4me.suggestion

/**
 * THE WORKS FITTED ON, SCORED BY THE MODEL KEPT ([scoring]), a portion at a time as the last walk of a fitting hands
 * them over ([add]): every item by the model of the fold that held it out, as the scoring kept scores it. Of every pair
 * the user answered on its score ([heldOut]: the items by their ids) — what the application calibrates the chances by;
 * and every value's share of the works ([shares]) — what the model tells of it over the works as a whole. Nothing of a
 * portion kept but these.
 */
internal class ScoredPortions(private val scoring: Scoring) {
    private val m = scoring.valueKeys.size

    /** Every value's chances summed over the works, and how many works. */
    private val sums = DoubleArray(m)
    private var works = 0

    /** The pairs answered on, one after another: the item's id, the value, the score. */
    private val items = ArrayList<Long>()
    private val values = ArrayList<Int>()
    private val scores = ArrayList<Float>()

    /**
     * The items of a [portion] scored: their chances added to the values' shares, a thousand items at a time, and the
     * scores of the pairs answered on kept.
     */
    fun add(portion: SuggestionTask) {
        scoring.on(portion).use { scored ->
            for (chunk in (0 until portion.itemCount).chunked(CHUNK)) {
                scored.of(chunk.toIntArray()).forEachIndexed { r, row -> addShares(portion, chunk[r], row) }
            }
            val answered = AnsweredPairs.of(portion, m)
            if (answered.size > 0) {
                val made = scored.scores(answered.items, answered.values.map { intArrayOf(it) })
                for (k in 0 until answered.size) {
                    items += portion.itemIds[answered.items[k]]
                    values += answered.values[k]
                    scores += made[k].single()
                }
            }
        }
        works += portion.itemCount
    }

    /**
     * The chances [row] of the item [i] of [portion] added to the sums of the shares. An item that may not have the
     * value at all ([SuggestionTask.allowed]: a pairing of characters it has not) adds nothing: the model never learns
     * of such a pair, its score there tells nothing.
     */
    private fun addShares(portion: SuggestionTask, i: Int, row: FloatArray) {
        val allowed = portion.allowed[i]
        if (allowed == null) for (j in 0 until m) sums[j] += row[j]
        else {
            allowed.forEach { j -> if (j in 0 until m) sums[j] += row[j] }
            // Of those, the ones the item may not have after all
            portion.barred[i].forEach { j -> if (j in 0 until m) sums[j] -= row[j] }
        }
    }

    /** Every value's share of the works: the sum of their chances of it over their number. */
    fun shares(): FloatArray {
        val n = works.coerceAtLeast(1)
        return FloatArray(m) { (sums[it] / n).toFloat() }
    }

    /** The scores of the pairs answered on, by the items' numbers, in the order the portions handed them over. */
    fun heldOut(): HeldOutScores = HeldOutScores(items.toLongArray(), values.toIntArray(), scores.toFloatArray())

    private companion object {
        /** The items scored at once for the shares: memory, not meaning. */
        const val CHUNK = 1000
    }
}
