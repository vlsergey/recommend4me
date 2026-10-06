package io.github.vlsergey.recommend4me.rating

/**
 * The user's grade of a work, 1..5: 1 — do not like it, 2 — can be played (read), 3 — liked it on
 * the whole, 4 — very good, 5 — give me more.
 *
 * A GRADE IS AN ORDER, NOT A NUMBER: the model learns which of two works is better, and where the
 * grades fall on its 0..10 is what the data says.
 */
object Grades {
    const val MIN = 1
    const val MAX = 5
    val RANGE = MIN..MAX

    /** Neither liked nor disliked: the grades above it are liked, the ones below disliked. */
    const val MIDDLE = 2

    /** The top of the scale the predictions are shown on. */
    const val MAX_SCORE = 10.0
}
