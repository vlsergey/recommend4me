package io.github.vlsergey.recommend4me.rating

/**
 * The grade of a work of a content type changed: the user graded or ungraded it — or its item now stands under the
 * grade of another work it was [merged] with, the user's word on a pair, no grade given.
 */
data class WorkGradeChangeEvent(val type: String, val source: String, val itemId: String, val merged: Boolean = false)
