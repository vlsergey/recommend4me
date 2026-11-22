package io.github.vlsergey.recommend4me.duplicate

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

    /**
     * The spread of the chances of the [found] pairs, as the search made them, of the sites of the works [counted];
     * none of models of no answer yet ([learnt]). A work's likeliest by its place among its site's.
     */
    fun of(found: Collection<FoundCandidate>, counted: List<Int>, learnt: Boolean): String? {
        if (!learnt) return null
        val same = IntArray(BANDS.size)
        val series = IntArray(BANDS.size)
        val best = counted.map { DoubleArray(it) { NONE } }
        found.forEach { f ->
            same[bandOf(f.same)]++
            f.series?.let { series[bandOf(it)]++ }
            best[f.site][f.item] = maxOf(best[f.site][f.item], f.same)
            best[f.otherSite][f.other] = maxOf(best[f.otherSite][f.other], f.same)
        }
        val works = IntArray(BANDS.size)
        var paired = 0
        best.forEach { site ->
            site.forEach {
                if (it != NONE) {
                    works[bandOf(it)]++
                    paired++
                }
            }
        }
        return "the chances of ${found.size} pairs of one work: ${told(same)}; of one series: ${told(series)}; " +
            "of $paired works, of their likeliest pair of one work: ${told(works)}"
    }

    /** The likeliest chance of a work of no pair. */
    private const val NONE = -1.0

    private const val PERCENT = 100
}
