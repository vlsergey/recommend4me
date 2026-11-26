package io.github.vlsergey.recommend4me.interest

/**
 * WHETHER A WORK IS INTERESTING TO READ AT ALL — beside how good it is, not instead of it: a work the model would grade
 * 10 may be of no interest to the user. The list sorted by the formula puts the works by the prediction times the
 * chance of interest.
 *
 * What the user said decides — the mark alone: a grade says nothing of interest, and a mark nothing of the grade (the
 * user's rule: what was once taken over from the grades stays as marks, nothing is taken over by itself). Every other
 * work has the chance the model of interest gives it, or none without one.
 */
object Interest {
    /** The chance of interest the list sorts by: 1 or 0 for what the user said, else the model's [modelChance]. */
    fun chance(mark: Boolean?, modelChance: Double?): Double? = mark?.let { if (it) 1.0 else 0.0 } ?: modelChance

    /**
     * What the list sorted by the formula sorts by: the [score] times the [chance] of interest — the score as the scale
     * shows it, below its 0 a 0: a chance multiplying a negative score would put the likelier interesting work lower —
     * or the score alone without a chance.
     */
    fun expected(score: Double, chance: Double?): Double =
        if (chance != null) score.coerceAtLeast(0.0) * chance else score
}

/**
 * The mark of interest of a work of a content type changed: the user marked or unmarked it — or its item now stands
 * under the mark of another work it was [merged] with.
 */
data class WorkInterestChangeEvent(
    val type: String,
    val source: String,
    val itemId: Long,
    val merged: Boolean = false,
)
