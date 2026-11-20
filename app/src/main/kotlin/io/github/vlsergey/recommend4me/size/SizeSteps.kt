package io.github.vlsergey.recommend4me.size

import kotlin.math.abs

/**
 * HOW FAR THE FEATURES OF THE WORDS TO A CHARACTER MOVE A WORK, told in the log (owner, 2026-11-20: "are all the
 * authors so different?"): of every kind of feature — the sites, the languages, the authors, the tags — and of the
 * authors by how many works teach their step, how many there are, the median and the 90th percentile of their step by a
 * logarithm (0.01 ≈ 1 %), and the share of them moving a work by more than 1 %.
 */
internal object SizeSteps {
    /** The steps [weights] of the features [names], of [examples] works each ([counts]). */
    fun told(weights: DoubleArray, names: List<String>, counts: IntArray): String {
        val kinds = LinkedHashMap<String, MutableList<Double>>()
        for (f in 1 until names.size) {
            val step = abs(weights[f])
            kinds.getOrPut(kindOf(names[f])) { ArrayList() } += step
            if (names[f].startsWith(WordsPerChar.AUTHOR))
                kinds.getOrPut("authors of ${bandOf(counts[f])} works") { ArrayList() } += step
        }
        return kinds.entries.joinToString("; ") { (kind, steps) -> "$kind ${spread(steps)}" }
    }

    private fun kindOf(name: String) =
        when {
            name.startsWith(WordsPerChar.SOURCE) -> "sites"
            name.startsWith(WordsPerChar.LANGUAGE) -> "languages"
            name.startsWith(WordsPerChar.AUTHOR) -> "authors"
            else -> "tags"
        }

    private fun bandOf(n: Int) =
        when {
            n <= 1 -> "1"
            n <= FEW -> "2–$FEW"
            n <= SOME -> "${FEW + 1}–$SOME"
            else -> "more"
        }

    private fun spread(steps: List<Double>): String {
        val sorted = steps.sorted()
        fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        val over = sorted.count { it > ONE_PER_CENT } * PER_CENT / sorted.size
        return "${sorted.size}: median %.4f, 90%% %.4f, over 1%% %.1f%%".format(at(MEDIAN), at(NINETIETH), over)
    }

    private const val FEW = 5
    private const val SOME = 20
    private const val MEDIAN = 0.5
    private const val NINETIETH = 0.9
    private const val ONE_PER_CENT = 0.01
    private const val PER_CENT = 100.0
}
