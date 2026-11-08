package io.github.vlsergey.recommend4me.suggestion

/**
 * THE PAIRS THAT WEIGH ANYTHING OF A TASK, LISTED ([SuggestionPairs.forEach]): every pair of an item — a value it
 * merely lacks and may have, its own — with its weight, label and whether the site gives it as an example; by the
 * items, their values ascending ([firstOfItem], [value]), and by the values, their items ascending ([firstOfValue],
 * [item]), each with its numbers. What the pairs on the card go through ([PairsOnCard]) when they are few of the items
 * × the values — of ficbook's characters a hundred thousand of ninety-three million, a work of a universe that
 * universe's characters alone, the rest of its row weighing nothing: not every pair gone through to find them.
 */
internal class ListedPairs
private constructor(
    val firstOfItem: LongArray,
    val value: IntArray,
    val weight: FloatArray,
    val label: FloatArray,
    val example: FloatArray,
    val firstOfValue: LongArray,
    val item: IntArray,
    val byValueWeight: FloatArray,
    val byValueLabel: FloatArray,
    val byValueExample: FloatArray,
) {
    companion object {
        /**
         * Of how many of the items × the values the pairs that weigh must be at most for their list to be gone through:
         * a pair of the list reads its other side's coordinates scattered, some times what a pair of the dense sweep
         * costs, which goes through every pair but each in order.
         */
        private const val SHARE = 8

        /** The pairs of [p] listed; null when they are too many for the list to be the cheaper ([SHARE]). */
        fun of(p: Problem): ListedPairs? {
            val pairs = p.pairs
            val count = pairs.count.sumOf { it.toLong() }
            if (count * SHARE > p.n.toLong() * p.m || count > Int.MAX_VALUE) return null
            val total = count.toInt()
            val first = LongArray(p.n + 1)
            val value = IntArray(total)
            val weight = FloatArray(total)
            val label = FloatArray(total)
            val example = FloatArray(total)
            val ofValue = IntArray(p.m)
            var at = 0
            for (i in 0 until p.n) {
                val examples = p.exampleValues[i]
                pairs.forEach(i) { v, w, y ->
                    if (w != 0f) {
                        value[at] = v
                        weight[at] = w
                        label[at] = y
                        example[at] = if (examples.binarySearch(v) >= 0) 1f else 0f
                        ofValue[v]++
                        at++
                    }
                }
                first[i + 1] = at.toLong()
            }
            // The same pairs by the values, the items of each in their order: counted, then laid out
            val firstOfValue = LongArray(p.m + 1)
            for (j in 0 until p.m) firstOfValue[j + 1] = firstOfValue[j] + ofValue[j]
            val next = IntArray(p.m) { firstOfValue[it].toInt() }
            val item = IntArray(at)
            val w = FloatArray(at)
            val l = FloatArray(at)
            val e = FloatArray(at)
            for (i in 0 until p.n) for (k in first[i].toInt() until first[i + 1].toInt()) {
                val to = next[value[k]]++
                item[to] = i
                w[to] = weight[k]
                l[to] = label[k]
                e[to] = example[k]
            }
            return ListedPairs(first, value, weight, label, example, firstOfValue, item, w, l, e)
        }
    }
}
