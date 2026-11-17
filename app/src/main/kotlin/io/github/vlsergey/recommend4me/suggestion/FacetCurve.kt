package io.github.vlsergey.recommend4me.suggestion

/**
 * THE CURVE OF A FACET'S CHANCES ([Calibration]): of every answer, the score against yes or no made monotone by pooling
 * the adjacent stretches that break the order — the higher score never the lower chance — each stretch's chance its
 * yeses among its answers, half a yes and half a no added: no chance of none or all is made of a few answers.
 */
internal object FacetCurve {

    /** A stretch of answers pooled: how many, how many yeses, the sum of their scores. */
    private class Stretch(var answers: Int, var yeses: Int, var scores: Double) {
        val chance: Double
            get() = (yeses + HALF) / (answers + 1)
    }

    /** The mean score of every stretch and its chance, ascending, of the [answered]. */
    fun of(answered: List<Answered>): Pair<FloatArray, FloatArray> {
        val stretches = ArrayList<Stretch>()
        answered
            .sortedBy { it.score }
            .forEach { a ->
                stretches += Stretch(1, if (a.yes) 1 else 0, a.score.toDouble())
                // The last two pooled while the later is not likelier
                while (stretches.size > 1 && stretches[stretches.size - 2].chance >= stretches.last().chance) {
                    val last = stretches.removeAt(stretches.size - 1)
                    stretches.last().apply {
                        answers += last.answers
                        yeses += last.yeses
                        scores += last.scores
                    }
                }
            }
        return FloatArray(stretches.size) { (stretches[it].scores / stretches[it].answers).toFloat() } to
            FloatArray(stretches.size) { stretches[it].chance.toFloat() }
    }

    /** Half a yes and half a no added to every stretch. */
    private const val HALF = 0.5
}
