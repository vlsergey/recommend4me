package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * HOW THE CHANCES OF THE PAIRS A SEARCH FOUND ARE SPREAD, told in the log: of the pairs, how many have a chance of one
 * work in every band ([BANDS]), and of one series; of the works, how many have their likeliest pair in every band —
 * what a least chance kept would leave (owner, 2026-11-20: "show me the spread of the chances").
 */
internal object ChanceSpread {
    /** The lower bounds of the bands, ascending; the last band up to 1. */
    private val BANDS = doubleArrayOf(0.0, 0.001, 0.01, 0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 0.9, 0.99)

    private fun bandOf(chance: Double): Int {
        var k = BANDS.size - 1
        while (k > 0 && chance < BANDS[k]) k--
        return k
    }

    private fun told(counts: IntArray): String =
        BANDS.indices.joinToString(", ") { k ->
            val to = if (k + 1 < BANDS.size) BANDS[k + 1] else 1.0
            "${percent(BANDS[k])}–${percent(to)}% ${counts[k]}"
        }

    private fun percent(x: Double) = (x * PERCENT).toBigDecimal().stripTrailingZeros().toPlainString()

    /** The spread of the chances by the [chances] of the user's answers of the [found] pairs; none of no model. */
    fun of(found: Collection<DuplicateCandidate>, chances: PairChances?): String? {
        if (chances?.learnt != true) return null
        val same = IntArray(BANDS.size)
        val series = IntArray(BANDS.size)
        val best = HashMap<ItemKey, Double>()
        found.forEach { c ->
            val p = chances.same(c)
            same[bandOf(p)]++
            chances.series(c)?.let { series[bandOf(it)]++ }
            best.merge(c.first, p, ::maxOf)
            best.merge(c.second, p, ::maxOf)
        }
        val works = IntArray(BANDS.size)
        best.values.forEach { works[bandOf(it)]++ }
        return "the chances of ${found.size} pairs of one work: ${told(same)}; of one series: ${told(series)}; " +
            "of ${best.size} works, of their likeliest pair of one work: ${told(works)}"
    }

    private const val PERCENT = 100
}
