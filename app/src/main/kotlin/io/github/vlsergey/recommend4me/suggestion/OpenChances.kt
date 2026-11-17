package io.github.vlsergey.recommend4me.suggestion

/**
 * THE CHANCES OF A FACET'S OPEN VALUES OF EVERY WORK — neither the site's, nor a draft, nor answered on: the model's to
 * give or not — counted as a pass scores its works a portion at a time ([add]), never held: of every one of [BINS]
 * equal stretches of the chances, how many fell in it, their sum and the lowest. What the facet's cutoff is worked out
 * of ([balanced]): of a million works, a list of every chance of every work was more than the memory.
 */
internal class OpenChances {
    private val counts = LongArray(BINS)
    private val sums = DoubleArray(BINS)
    private val lowest = DoubleArray(BINS) { Double.POSITIVE_INFINITY }

    /** How many chances were counted. */
    var size = 0L
        private set

    fun add(chance: Double) {
        val bin = (chance * BINS).toInt().coerceIn(0, BINS - 1)
        counts[bin]++
        sums[bin] += chance
        if (chance < lowest[bin]) lowest[bin] = chance
        size++
    }

    /**
     * THE CUTOFF RATHER SHOWING WRONGLY THAN HIDING ([Calibration]): of the stretches from the highest down, the lowest
     * chance of the first at which those at or above it expected wrongly shown — the sum of one less each — are not
     * fewer than those under it expected wrongly hidden — the sum of each. Within a stretch of the cutoff of every
     * chance held ([BINS]); one half of none.
     */
    fun balanced(): Double {
        var hidden = sums.sum()
        var shown = 0.0
        var found: Double? = null
        var last: Double? = null
        var bin = BINS - 1
        while (bin >= 0 && found == null) {
            if (counts[bin] > 0) {
                hidden -= sums[bin]
                shown += counts[bin] - sums[bin]
                last = lowest[bin]
                if (shown >= hidden) found = lowest[bin]
            }
            bin--
        }
        return found ?: last ?: NONE
    }

    companion object {
        /** The stretches of the chances: the cutoff is worked out within one of them, a ten-thousandth. */
        const val BINS = 10_000

        /** The cutoff of no chance: one half, as a suggester's own. */
        private const val NONE = 0.5
    }
}
